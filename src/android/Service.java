package com.heytz.pushService;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.util.Log;
import com.heytz.delinb.MainActivity;
import com.heytz.delinb.R;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.*;

import java.io.IOException;

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
    private static final long KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

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

        try {
            mLog = new ConnectionLog();
            Log.i(TAG, "Opened log at " + mLog.getPath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to open log", e);
        }

        // Get instances of preferences, connectivity manager and notification manager
        mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
        mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        mNotifMan = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		/* If our process was reaped by the system for any reason we need
         * to restore our state with merely a call to onCreate.  We record
		 * the last "started" value and restore it here if necessary. */
        handleCrashedService();
    }

    // This method does any necessary clean-up need in case the server has been destroyed by the system
    // and then restarted
    private void handleCrashedService() {
        if (wasStarted() == true) {
            log("Handling crashed service...");
            // stop the keep alives
            stopKeepAlives();

            // Do a clean start
            start();
        }
    }

    @Override
    public void onDestroy() {
        log("Service destroyed (started=" + mStarted + ")");

        // Stop the services, if it has been started
        if (mStarted == true) {
            stop();
        }

        try {
            if (mLog != null)
                mLog.close();
        } catch (IOException e) {
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        log("Service started with intent=" + intent);

        // Do an appropriate action based on the intent.
        if (intent.getAction().equals(ACTION_STOP) == true) {
            stop();
            stopSelf();
        } else if (intent.getAction().equals(ACTION_START) == true) {
            start();
        } else if (intent.getAction().equals(ACTION_KEEPALIVE) == true) {
            keepAlive();
        } else if (intent.getAction().equals(ACTION_RECONNECT) == true) {
            if (isNetworkAvailable()) {
                reconnectIfNecessary();
            }
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

        // Register a connectivity listener
        registerReceiver(mConnectivityChanged, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
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
        unregisterReceiver(mConnectivityChanged);
        // Any existing reconnect timers should be removed, since we explicitly stopping the service.
        cancelReconnect();

        // Destroy the MQTT connection if there is one
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
        // fetch the device ID from the preferences.
//        String deviceID = mPrefs.getString(PREF_DEVICE_ID, null);
        // Create a new connection only if the device id is not NULL
//        if (deviceID == null) {
//            log("Device ID not found.");
//        } else {
//        try {
        new Thread() {
            @Override
            public void run() {
                try {
//                    mConnection = new MQTTConnection(MQTT_HOST, MQTT_TOPIC);
                    connect(MQTT_URL, USERNAME, PASSWORD);
                } catch (Exception e) {
                    log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);
                }
            }
        }.start();
//        } catch (MqttException e) {
//            // Schedule a reconnect, if we failed to connect
//            log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"));
//            if (isNetworkAvailable()) {
//                scheduleReconnect(mStartTime);
//            }
//        }
        setStarted(true);
//        }
    }

    private synchronized void keepAlive() {
//        try {
        // Send a keep alive, if there is a connection.
//            if (mStarted == true && mConnection != null) {
//                mConnection.sendKeepAlive();
//            }
//        } catch (MqttException e) {
//            log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);

//            mConnection.disconnect();
//            mConnection = null;
//            cancelReconnect();
//        }
    }

    // Schedule application level keep-alives using the AlarmManager
    private void startKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, Service.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + KEEP_ALIVE_INTERVAL,
                KEEP_ALIVE_INTERVAL, pi);
    }

    // Remove all scheduled keep alives
    private void stopKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, Service.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.cancel(pi);
    }

    // We schedule a reconnect based on the starttime of the service
    public void scheduleReconnect(long startTime) {
        // the last keep-alive interval
        long interval = mPrefs.getLong(PREF_RETRY, INITIAL_RETRY_INTERVAL);

        // Calculate the elapsed time since the start
        long now = System.currentTimeMillis();
        long elapsed = now - startTime;


        // Set an appropriate interval based on the elapsed time since start
        if (elapsed < interval) {
            interval = Math.min(interval * 4, MAXIMUM_RETRY_INTERVAL);
        } else {
            interval = INITIAL_RETRY_INTERVAL;
        }

        log("Rescheduling connection in " + interval + "ms.");

        // Save the new internval
        mPrefs.edit().putLong(PREF_RETRY, interval).commit();

        // Schedule a reconnect using the alarm manager.
        Intent i = new Intent();
        i.setClass(this, Service.class);
        i.setAction(ACTION_RECONNECT);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
    }

    // Remove the scheduled reconnect
    public void cancelReconnect() {
        Intent i = new Intent();
        i.setClass(this, Service.class);
        i.setAction(ACTION_RECONNECT);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.cancel(pi);
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
            // Get network info
            NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);

            // Is there connectivity?
            boolean hasConnectivity = (info != null && info.isConnected()) ? true : false;

            log("Connectivity changed: connected=" + hasConnectivity);

//            if (hasConnectivity) {
            reconnectIfNecessary();
//            } else if (mConnection != null) {
            // if there no connectivity, make sure MQTT connection is destroyed
//                mConnection.disconnect();
            cancelReconnect();
//                mConnection = null;
//            }
        }
    };

    // Display the topbar notification
    private void showNotification(String text) {
//        Notification n = new Notification();

//        n.flags |= Notification.FLAG_SHOW_LIGHTS;
//        n.flags |= Notification.FLAG_AUTO_CANCEL;

//        n.defaults = Notification.DEFAULT_ALL;

//        n.when = System.currentTimeMillis();

        // Simply open the parent activity

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

//        PendingIntent pi = PendingIntent.getActivity(this, 0,
//                new Intent(this, Service.class), 0);

        // Change the name of the notification here
        Notification n = new Notification.Builder(this)
                .setDefaults(Notification.DEFAULT_ALL)
                .setSmallIcon(R.drawable.icon)
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
            String clientId = client.generateClientId();
            String willTopic = MQTT_WILL_TOPIC;
            String willPayload = "TEST";
            boolean willRetain = false;
            int willQos = 0;
            connOpts.setCleanSession(MQTT_CLEAN_START);
            connOpts.setKeepAliveInterval(MQTT_KEEP_ALIVE);
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

//                connOpts.setWill(willTopic, willPayload.getBytes(), willQos, willRetain);
            }

            if (username.toString() == "null" && password.toString() == "null") {
                Log.i("mqttalabs", "not applying creds");

            } else {
                Log.i("mqttalabs", "applying creds");
                connOpts.setUserName(username);
                connOpts.setPassword(password.toCharArray());
            }
            //connOpts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1);
            connOpts.setConnectionTimeout(MQTT_TIME_OUT);
            client.connect(connOpts, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    connected = true;
                    subscribe(MQTT_TOPIC + "/#");

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
//    private class MQTTConnection implements MqttSimpleCallback {
//        IMqttClient mqttClient = null;
//
//        // Creates a new connection given the broker address and initial topic
//        public MQTTConnection(String brokerHostName, String initTopic) throws MqttException {
//            // Create connection spec
//            String mqttConnSpec = "tcp://" + brokerHostName + "@" + MQTT_BROKER_PORT_NUM;
//            // Create the client and connect
//            mqttClient = MqttClient.createMqttClient(mqttConnSpec, MQTT_PERSISTENCE);
//            String clientID = UUID.randomUUID().toString().substring(0,7);
//
//            mqttClient.connect(clientID, MQTT_CLEAN_START, MQTT_KEEP_ALIVE);
//
//            // register this client app has being able to receive messages
//            mqttClient.registerSimpleHandler(this);
//
//            // Subscribe to an initial topic, which is combination of client ID and device ID.
//            initTopic = initTopic + "/#";
//            subscribeToTopic(initTopic);
//
//            log("Connection established to " + brokerHostName + " on topic " + initTopic);
//
//            // Save start time
//            mStartTime = System.currentTimeMillis();
//            // Star the keep-alives
//            startKeepAlives();
//        }
//
//        // Disconnect
//        public void disconnect() {
//            try {
//                stopKeepAlives();
//                mqttClient.disconnect();
//            } catch (MqttPersistenceException e) {
//                log("MqttException" + (e.getMessage() != null ? e.getMessage() : " NULL"), e);
//            }
//        }
//
//        /*
//         * Send a request to the message broker to be sent messages published with
//         *  the specified topic name. Wildcards are allowed.
//         */
//        private void subscribeToTopic(String topicName) throws MqttException {
//
//            if ((mqttClient == null) || (mqttClient.isConnected() == false)) {
//                // quick sanity check - don't try and subscribe if we don't have
//                //  a connection
//                log("Connection error" + "No connection");
//            } else {
//                String[] topics = {topicName};
//                mqttClient.subscribe(topics, MQTT_QUALITIES_OF_SERVICE);
//            }
//        }
//
//        /*
//         * Sends a message to the message broker, requesting that it be published
//         *  to the specified topic.
//         */
//        private void publishToTopic(String topicName, String message) throws MqttException {
//            if ((mqttClient == null) || (mqttClient.isConnected() == false)) {
//                // quick sanity check - don't try and publish if we don't have
//                //  a connection
//                log("No connection to public to");
//            } else {
//                mqttClient.publish(topicName,
//                        message.getBytes(),
//                        MQTT_QUALITY_OF_SERVICE,
//                        MQTT_RETAINED_PUBLISH);
//            }
//        }
//
//        /*
//         * Called if the application loses it's connection to the message broker.
//         */
//        public void connectionLost() throws Exception {
//            log("Loss of connection" + "connection downed");
//            if (!mConnection.mqttClient.isConnected()) {
//                stopKeepAlives();
//                // null itself
//                mConnection = null;
//                if (isNetworkAvailable() == true) {
//                    reconnectIfNecessary();
//                }
//            }
//        }
//
//        /*
//         * Called when we receive a message from the message broker.
//         */
//        public void publishArrived(String topicName, byte[] payload, int qos, boolean retained) {
//            // Show a notification
//            String s = new String(payload);
//            showNotification(s);
//            log("Got message: " + s);
//        }
//
//        public void sendKeepAlive() throws MqttException {
//            log("Sending keep alive");
//            // publish to a keep-alive topic
//            publishToTopic(MQTT_CLIENT_ID + "/keepalive", mPrefs.getString(PREF_DEVICE_ID, ""));
//        }
//    }
}