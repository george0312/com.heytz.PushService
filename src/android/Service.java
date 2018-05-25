package com.heytz.pushService;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import com.heytz.hbao.MainActivity;
import com.heytz.hbao.R;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

//import com.ibm.mqtt.*;

/*
 * PushService that does all of the work.
 * Most of the logic is borrowed from KeepAliveService.
 * http://code.google.com/p/android-random/source/browse/trunk/TestKeepAlive/src/org/devtcg/demo/keepalive/KeepAliveService.java?r=219
 */
public class Service extends android.app.Service {
    // this is the log tag
    public static final String TAG = "PushService";

    // the IP address, where your MQTT broker is running.
    private static String MQTT_HOST = "";
    private static String MQTT_URL = "";
    private static String MQTT_TOPIC = "";
    // the port at which the broker is running.
    private static int MQTT_BROKER_PORT_NUM = 2883;
    private static String USERNAME = "";
    private static String PASSWORD = "";

    // Let's not use the MQTT persistence.
//    private static MqttPersistence MQTT_PERSISTENCE = null;
    // We don't need to remember any state between the connections, so we use a clean start.
    private static boolean MQTT_CLEAN_START = true;
    private static short MQTT_KEEP_ALIVE = 60 * 15;
    private static short MQTT_TIME_OUT = 30;
    private static String MQTT_WILL_TOPIC = "";
    // Set quality of services to 0 (at most once delivery), since we don't want push notifications
    // arrive more than once. However, this means that some messages might get lost (delivery is not guaranteed)
    private static int[] MQTT_QUALITIES_OF_SERVICE = {0};
    private static int MQTT_QUALITY_OF_SERVICE = 0;
    private int notifyId = 0;
    // The broker should not retain any messages.
    private static boolean MQTT_RETAINED_PUBLISH = false;

    // MQTT client ID, which is given the broker. In this example, I also use this for the topic header.
    // You can use this to run push notifications for multiple apps with one MQTT broker.
    public static String MQTT_CLIENT_ID = "heytz";

    // These are the actions for the service (name are descriptive enough)
    private static final String ACTION_START = MQTT_CLIENT_ID + ".START";
    private static final String ACTION_STOP = MQTT_CLIENT_ID + ".STOP";
    private static final String ACTION_KEEPALIVE = MQTT_CLIENT_ID + ".KEEP_ALIVE";
    private static final String ACTION_RECONNECT = MQTT_CLIENT_ID + ".RECONNECT";

    // Connection log for the push service. Good for debugging.
    private ConnectionLog mLog;

    // Connectivity manager to determining, when the phone loses connection
    private ConnectivityManager mConnMan;
    // Notification manager to displaying arrived push notifications
    private NotificationManager mNotifMan;

    // Whether or not the service has been started.
    private boolean mStarted;

    // This the application level keep-alive interval, that is used by the AlarmManager
    // to keep the connection active, even when the device goes to sleep.
    private static final long KEEP_ALIVE_INTERVAL = 1000 * 30;

    // Retry intervals, when the connection is lost.
    private static final long INITIAL_RETRY_INTERVAL = 1000 * 10;
    private static final long MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 30;

    // Preferences instance
    private SharedPreferences mPrefs;
    // We store in the preferences, whether or not the service has been started
    public static final String PREF_STARTED = "isStarted";
    // We also store the deviceID (target)
    public static final String PREF_DEVICE_ID = "deviceID";
    // We store the last retry interval
    public static final String PREF_RETRY = "retryInterval";

    // Notification title
    public static String NOTIF_TITLE = "Heytz";
    // Notification id
    private static final int NOTIF_CONNECTED = 0;

    // This is the instance of an MQTT connection.
//    private static MQTTConnection mConnection;
    private MqttAsyncClient client;
    private boolean connected;
    private long mStartTime;
    private Timer timer;
    private WakeLock wakeLock = null;

    // Static method to start the service
    public static void actionStart(Context ctx, String url, String topic, String username, String password) {
        MQTT_URL = url;
        MQTT_TOPIC = topic;
        USERNAME = username;
        PASSWORD = password;
        Intent i = new Intent(ctx, Service.class);
        i.setAction(ACTION_START);
        ctx.startService(i);
    }

    // Static method to stop the service
    public static void actionStop(Context ctx) {
        Intent i = new Intent(ctx, Service.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // Static method to send a keep alive message
    public static void actionPing(Context ctx) {
        Intent i = new Intent(ctx, Service.class);
        i.setAction(ACTION_KEEPALIVE);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        log("Creating service");
        mStartTime = System.currentTimeMillis();
        mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
        mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        mNotifMan = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        registerReceiver(mConnectivityChanged, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        registerReceiver(mScreenOffChanged, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        registerReceiver(mScreenOnChanged, new IntentFilter(Intent.ACTION_SCREEN_ON));

		/* If our process was reaped by the system for any reason we need
         * to restore our state with merely a call to onCreate.  We record
		 * the last "started" value and restore it here if necessary. */
//        handleCrashedService();
    }

    private void acquireWakeLock() {
        if (null == wakeLock) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, getClass()
                    .getCanonicalName());
            if (null != wakeLock) {
                Log.i(TAG, "call acquireWakeLock");
                wakeLock.acquire();
            }
        }
    }

    private void releaseWakeLock() {
        if (null != wakeLock && wakeLock.isHeld()) {
            Log.i(TAG, "call releaseWakeLock");
            wakeLock.release();
            wakeLock = null;
        }
    }

    class RemindTask extends TimerTask {
        public void run() {
            System.out.println("timer task running");
            reconnectIfNecessary();
            timer.schedule(new RemindTask(),  1000 * 5);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getAction().equals(ACTION_STOP) == true) {
            stop();
            stopSelf();
            releaseWakeLock();
        } else if (intent.getAction().equals(ACTION_START) == true) {
            start();
            acquireWakeLock();
        }
        flags = START_STICKY;
        return super.onStartCommand(intent, flags, startId);
    }

    // This method does any necessary clean-up need in case the server has been destroyed by the system
    // and then restarted

    @Override
    public void onDestroy() {
        log("Service destroyed (started=" + mStarted + ")");

        // Stop the services, if it has been started
        if (mStarted == true) {
//            stop();
        }
        unregisterReceiver(mConnectivityChanged);
        unregisterReceiver(mScreenOffChanged);
        unregisterReceiver(mScreenOnChanged);
        EventBus.getDefault().unregister(this);
        try {
            if (mLog != null)
                mLog.close();
        } catch (IOException e) {
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // log helper function
    private void log(String message) {
        log(message, null);
    }

    private void log(String message, Throwable e) {
        if (e != null) {
            Log.e(TAG, message, e);

        } else {
            Log.i(TAG, message);
        }

        if (mLog != null) {
            try {
                mLog.println(message);
            } catch (IOException ex) {
            }
        }
    }

    // Reads whether or not the service has been started from the preferences
    private boolean wasStarted() {
        return mPrefs.getBoolean(PREF_STARTED, false);
    }

    // Sets whether or not the services has been started in the preferences.
    private void setStarted(boolean started) {
        mPrefs.edit().putBoolean(PREF_STARTED, started).commit();
        mStarted = started;
    }

    private synchronized void start() {
        log("Starting service...");

        // Do nothing, if the service is already running.
        if (mStarted == true) {
            Log.w(TAG, "Attempt to start connection that is already active");
            return;
        }

        // Establish an MQTT connection
        connect();
        timer = new Timer();
        timer.schedule(new RemindTask(), KEEP_ALIVE_INTERVAL);
    }

    private synchronized void stop() {
        // Do nothing, if the service is not running.
        if (mStarted == false) {
            Log.w(TAG, "Attempt to stop connection not active.");
            return;
        }

        // Save stopped state in the preferences
        setStarted(false);

        // Remove the connectivity receiver
//         unregisterReceiver(mConnectivityChanged);
//         unregisterReceiver(mScreenOffChanged);
//         unregisterReceiver(mScreenOnChanged);
//         EventBus.getDefault().unregister(this);

        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {

            }
            client = null;
        }
    }

    //
    private synchronized void connect() {
        log("Connecting...");
//        new Thread() {
//            @Override
//            public void run() {
//                try {
        connect(MQTT_URL, USERNAME, PASSWORD);
//                } catch (Exception e) {
//                    log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);
//                }
//            }
//        }.start();
        setStarted(true);
    }


    // Schedule application level keep-alives using the AlarmManager
    private void startKeepAlives(final MqttAsyncClient client, final String clientId) {
        new Thread() {
            @Override
            public void run() {
                try {
                    while (client.isConnected()) {
                        sleep(30000);
                        MqttMessage aliveMessage = new MqttMessage();
                        client.publish(clientId, aliveMessage);
                    }
                } catch (Exception e) {
                    log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);
                }
            }
        }.start();
    }


    private synchronized void reconnectIfNecessary() {
//        if (mStarted == true && mConnection == null) {
        log("Reconnecting...");
        connect();
//        }
    }

    // This receiver listeners for network changes and updates the MQTT connection
    // accordingly
    private BroadcastReceiver mConnectivityChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("Connectivity changed: connected=");
            reconnectIfNecessary();
        }
    };

    private BroadcastReceiver mScreenOffChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("screen off changed: ");
            Intent aIntent = new Intent(getApplicationContext(), KeepAlive_Activity.class);
            aIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(aIntent);
        }
    };

    private BroadcastReceiver mScreenOnChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("screen on changed: ");
            EventBus.getDefault().post(new EventMessage(0));
        }
    };

    // Display the topbar notification
    private void showNotification(String text) {
        String title = text;
        String content = text;
        String ticker = text;
        String page = "";
        try {
            JSONObject notifyObj = new JSONObject(text);
            title = notifyObj.getString("title");
            content = notifyObj.getString("content");
            ticker = notifyObj.getString("ticker");
            page = notifyObj.getString("page");
        } catch (Exception e) {

        }
        Intent intent = new Intent(this, MainActivity.class);
        if (page != "") {
            intent.setAction("NOTI#" + page + "#" + notifyId);
        }
        PendingIntent pi = PendingIntent.getActivities(this, notifyId, new Intent[]{intent}, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this)
                .setDefaults(Notification.DEFAULT_ALL)
                .setSmallIcon(R.mipmap.icon)
                .setTicker(ticker)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pi).build();
        mNotifMan.notify(notifyId, n);
        notifyId++;

    }

    // Check if we are online
    private boolean isNetworkAvailable() {
        NetworkInfo info = mConnMan.getActiveNetworkInfo();
        if (info == null) {
            return false;
        }
        return info.isConnected();
    }

    // This inner class is a wrapper on top of MQTT client.
    private void connect(String url, String username, String password) {
        MemoryPersistence persistence = new MemoryPersistence();
        final MqttConnectOptions connOpts = new MqttConnectOptions();
        connected = false;
        try {
            Log.i("mqttalabs", "========connecting");

            final String clientId = client.generateClientId();
            String willTopic = MQTT_WILL_TOPIC;
            String willPayload = "TEST";
            boolean willRetain = false;
            int willQos = 0;
            connOpts.setCleanSession(MQTT_CLEAN_START);
            connOpts.setKeepAliveInterval(MQTT_KEEP_ALIVE);
            if (client != null && client.isConnected()) {
                return;
            }
            client = new MqttAsyncClient(url, clientId, persistence);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    connected = false;
                    Log.i("mqttalabs", cause.toString());
                    if (isNetworkAvailable()) {
                        reconnectIfNecessary();
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    Log.i("mqttalabs", "topic is " + topic + ". payload is " + message.toString());
                    String s = message.toString();
                    showNotification(s);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    try {
                        token.waitForCompletion();
                    } catch (MqttException e) {
                        e.printStackTrace();
                    }
                }
            });
            if (willTopic != null && willPayload != null && willQos > -1) {

            }

            if (username.toString() == "null" && password.toString() == "null") {
                Log.i("mqttalabs", "not applying creds");

            } else {
                Log.i("mqttalabs", "applying creds");
                connOpts.setUserName(username);
                connOpts.setPassword(password.toCharArray());
            }
            connOpts.setConnectionTimeout(MQTT_TIME_OUT);
            client.connect(connOpts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    connected = true;
                    subscribe(MQTT_TOPIC + "/#");
                    startKeepAlives(client, clientId);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    connected = false;

                }
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void subscribe(final String topic) {
        try {
            client.subscribe(topic, 0, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {

                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {

                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
