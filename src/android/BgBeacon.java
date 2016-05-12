package com.hinohunomi.bgbeacon;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;

public class BgBeacon extends CordovaPlugin {
    private static final String TAG = "BgBeacon";
    
    //Service
    BeaconService mService;
    boolean mBound = false;

    public BgBeacon() {
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.d(TAG, "initialize");
        //When bind to service, to stop the beacon detection
        Context applicationContext = cordova.getActivity().getApplicationContext();
        Intent intent = new Intent(applicationContext, BeaconService.class);
        applicationContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        StartupReceiver.StartBeaconService(applicationContext);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            Log.d(TAG, "onServiceConnected");
            BeaconService.LocalBinder binder = (BeaconService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG, "onServiceDisconnected");
            mBound = false;
        }
    };
}
