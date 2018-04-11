package com.heytz.pushService;

import android.content.Context;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import cn.jpush.android.api.JPushInterface;

/**
 * This class starts transmit to activation
 */
public class ServiceWrapper extends CordovaPlugin {

    private static String TAG = "=====ServiceWrapper.class====";
    private CallbackContext socketCallbackContext;
    private Context context;


    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        context = cordova.getActivity().getApplicationContext();
        JPushInterface.init(context);
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        socketCallbackContext = callbackContext;
        if (action.equals("stopService")) {
            Service.actionStop(context);
            return true;
        }
        if (action.equals("initService")) {
            String host = args.getString(0);
            String port = args.getString(1);
            String url = "tcp://" + host + ":" + port;
            String topic = args.getString(2);
            String username = args.getString(3);
            String password = args.getString(4);
            Service.actionStart(context, url, topic, username, password);

            return true;
        }
        return false;
    }

//      public static void HideKeyboard(View v)
//      {
//          InputMethodManager imm = ( InputMethodManager ) v.getContext( ).getSystemService( Context.INPUT_METHOD_SERVICE );
//        if ( imm.isActive( ) ) {
//            imm.hideSoftInputFromWindow( v.getApplicationWindowToken( ) , 0 );
//
//        }
//      }
}
