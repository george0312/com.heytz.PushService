



#Android

    Android 如何实现推送跳转 
    
    需要在 MainActivity.java 引入 
    
    package _____PACKAGE_NAME_____;
    
    import android.app.NotificationManager;
    import android.content.Intent;
    import android.os.Bundle;
    import org.apache.cordova.*;
    
    public class MainActivity extends CordovaActivity
    {
        @Override
        public void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
    
            // enable Cordova apps to be started in the background
            Bundle extras = getIntent().getExtras();
            if (extras != null && extras.getBoolean("cdvStartInBackground", false)) {
                moveTaskToBack(true);
            }
            String action = this.getIntent().getAction();
            if (action != null && action.indexOf("NOTI#") != -1) {
                NotificationManager mNotifMan = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                String[] actionArr = action.split("#");
                mNotifMan.cancel(Integer.parseInt(actionArr[2]));
                launchUrl = launchUrl + actionArr[1];
            }
            // Set by <content src="index.html" /> in config.xml
            loadUrl(launchUrl);
        }
        @Override
        public void onNewIntent(Intent intent) {
            String action = intent.getAction();
            if (action != null && action.indexOf("NOTI#") != -1) {
                NotificationManager mNotifMan = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                String[] actionArr = action.split("#");
                mNotifMan.cancel(Integer.parseInt(actionArr[2]));
                String rootUrl = launchUrl.substring(0, launchUrl.indexOf("/", 8) + 1);
                rootUrl = rootUrl + actionArr[1];
                final String page=actionArr[1];
                loadUrl("javascript:" + "Router.go('" + page + "')");
            }
            super.onNewIntent(intent);
        }
    }
