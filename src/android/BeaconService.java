package com.hinohunomi.bgbeacon;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.startup.BootstrapNotifier;
import org.altbeacon.beacon.startup.RegionBootstrap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class BeaconService extends Service {
    private final static String TAG = "BeaconService";
    private final IBinder mBinder = new LocalBinder();
    private final Config config = new Config();
    private Timer mTimer;
    private BroadcastReceiver broadcastReceiver;

    //Thread
    private Looper mLooper;
    private ActionHandler mActionHandler;

    public class LocalBinder extends Binder {
        BeaconService getService() {
            return BeaconService.this;
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

        //Config
        config.loadMetaData(this);
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        boolean disableNotifications =  pref.getBoolean("com.hinohunomi.bgbeacon.disableNotifications", false);
        if (disableNotifications) {
            Log.d(TAG, "disableNotifications");
            return;
        }

        //Thread
        HandlerThread thread = new HandlerThread("ServiceWorkThread",
                10/*Process.THREAD_PRIORITY_BACKGROUND*/);
        thread.start();
        mLooper = thread.getLooper();
        mActionHandler = new ActionHandler(mLooper);
        mActionHandler.context = this;

        //Request Timer
        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mActionHandler.sendEmptyMessage(ActionHandler.ACT_REQUEST);
            }
        }, 1000/*, 1000*60*60*/);//just yet


        //watch connection
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "receive: CONNECTIVITY_CHANGE");
                if (BeaconService.isConnectedNetwork(context)) {
                    mActionHandler.sendEmptyMessage(ActionHandler.ACT_ONLINE_NOW);
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(broadcastReceiver, intentFilter);
    }

    private static boolean isConnectedNetwork(Context context) {
        boolean result = false;
        ConnectivityManager cm =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo info = cm.getActiveNetworkInfo();
        if (info != null) {
            result = info.isConnectedOrConnecting();
        }
        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        mActionHandler.sendEmptyMessage(ActionHandler.ACT_BEACON_DISABLE);
        return mBinder;
    }
    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind");
        return false;
    }
    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "onRebind");
    }
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        unregisterReceiver(broadcastReceiver);
        mActionHandler.abortBeacon();
    }

    private final class ActionHandler extends Handler implements BootstrapNotifier {
        static public final int ACT_REQUEST = 101;
        static public final int ACT_ONLINE_NOW = 102;
        static public final int ACT_BEACON_DISABLE = 103;

        private BeaconService context;
        private WaitState state = WaitState.WAIT_RESPONSE;
        private final RetryTime retryTime = new RetryTime();

        //Beacon
        private static final String IBEACON_FORMAT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24";
        private BeaconManager beaconManager;
        private RegionBootstrap regionBootstrap;
        private boolean beaconEnabled = true;
        private List<BeaconInfo> beaconList;

        public ActionHandler(Looper looper) {
            super(looper);
            Log.d(TAG, "ActionHandler");
        }
        public void abortBeacon() {
            this.beaconEnabled = false;
            if (regionBootstrap!= null) regionBootstrap.disable();
        }
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage: " + msg.what);
            synchronized (this) {
                switch (msg.what) {
                    case ACT_REQUEST:
                        actionRequest();
                        break;
                    case ACT_ONLINE_NOW:
                        actionSwitchOnline();
                        break;
                    case ACT_BEACON_DISABLE:
                        abortBeacon();
                        break;
                    default:
                        break;
                }
            }
        }

        private void actionRequest() {
            Log.d(TAG, "actionRequest");
            if (state != WaitState.WAIT_RESPONSE) {
                return;
            }
            if (!BeaconService.isConnectedNetwork(context)) {
                actionWaitOnline();
                return;
            }
            HttpURLConnection con = null;
            URL url;
            String urlSt = config.getServerUrl();
            InputStream in = null;
            boolean hasError = true;

            try {
                url = new URL(urlSt);
                con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setInstanceFollowRedirects(false);
                con.setDoInput(true);

                con.setConnectTimeout(config.getServerConnectTimeout());
                con.setReadTimeout(config.getServerReadTimeout());
                con.connect();
                Log.d(TAG, "ResponseCode: " + con.getResponseCode());
                if (con.getResponseCode() == 200) {
                    in = con.getInputStream();
                    String readSt = readInputStream(in);
//                    Log.v(TAG, readSt);
                    setupBeaconInfo(parseJSON(readSt));
                    actionStartMonitoring();
                    actionWaitTimer();
                    //reset error
                    hasError = false;
                    retryTime.reset();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (con != null) {
                    con.disconnect();
                }
            }

            //error retry
            if (hasError) {
                actionWaitRetry();
            }
        }

        private String readInputStream(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            String st;

            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            while((st = br.readLine()) != null)
            {
                sb.append(st);
            }
            try
            {
                in.close();
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

            return sb.toString();
        }

        private List<BeaconInfo> parseJSON(String jsonStr) throws Exception {
            //Log.d(TAG, jsonStr);
            List<BeaconInfo> result = new ArrayList<BeaconInfo>();

            JSONObject json = new JSONObject(jsonStr);
            //Log.d(TAG, json.toString(4));
            if (!"success".equals(json.getString("status"))) throw new Exception("status is not success");
            JSONArray ar = json.getJSONArray("beaconInfo");
            int arlen = ar.length();
            for (int i = 0; i < arlen; i++) {
                JSONObject o = ar.getJSONObject(i);
                BeaconInfo bi = new BeaconInfo();
                bi.identifier = o.getString("identifier");
                bi.uuid = o.getString("uuid");
                bi.major = o.getString("major");
                bi.minor = o.getString("minor");
                //Log.d(TAG, String.format("%s %s %s %s", bi.identifier, bi.uuid, bi.major, bi.minor));
                result.add(bi);
            }

            return result;
        }

        private void setupBeaconInfo(List<BeaconInfo> list) {
            this.beaconList = list;
        }

        private void actionStartMonitoring() {
            Log.d(TAG, "actionStartMonitoring");
            setupBeaconManager(this.beaconList);
        }

        private void actionWaitRetry() {
            Log.d(TAG, "actionWaitRetry");
            int delay = retryTime.nextPeriod();
            Log.d(TAG, "delay: " + String.valueOf(delay));
            this.sendEmptyMessageDelayed(ACT_REQUEST, delay);
        }

        private void actionWaitTimer() {
            Log.d(TAG, "actionWaitTimer");
            this.state = WaitState.WAIT_TIMER;
            Log.d(TAG, "state: " + this.state);
        }

        private void actionWaitOnline() {
            Log.d(TAG, "actionWaitOnline");
            this.state = WaitState.WAIT_ONLINE;
            Log.d(TAG, "state: " + this.state);
        }

        private void actionSwitchOnline() {
            Log.d(TAG, "actionSwitchOnline");
            if (this.state == WaitState.WAIT_ONLINE) {
                this.state = WaitState.WAIT_RESPONSE;
                Log.d(TAG, "state: " + this.state);
                this.retryTime.reset();
                actionRequest();
            }
        }

        private void setupBeaconManager(List<BeaconInfo> beaconList) {
            beaconManager = BeaconManager.getInstanceForApplication(context);
            BeaconManager.setDebug(false);
//        beaconManager.getBeaconParsers().clear();
            beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout(IBEACON_FORMAT));
            beaconManager.setForegroundScanPeriod(BeaconManager.DEFAULT_FOREGROUND_SCAN_PERIOD);
            beaconManager.setForegroundBetweenScanPeriod(BeaconManager.DEFAULT_FOREGROUND_BETWEEN_SCAN_PERIOD);
            //Also processed in the foreground and the same frequency in the background state
            beaconManager.setBackgroundScanPeriod(BeaconManager.DEFAULT_FOREGROUND_SCAN_PERIOD);
            beaconManager.setBackgroundBetweenScanPeriod(BeaconManager.DEFAULT_FOREGROUND_BETWEEN_SCAN_PERIOD);

            List<Region> regionList = new ArrayList<Region>();
            for (BeaconInfo bi : beaconList) {
                try {
                    Identifier uuid = Identifier.parse(bi.uuid.toUpperCase());
                    Identifier major = Identifier.parse(bi.major);
                    Identifier minor = Identifier.parse(bi.minor);
                    Region region = new Region(bi.identifier, uuid, major, minor);
                    regionList.add(region);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (regionList.size() > 0) {
                regionBootstrap = new RegionBootstrap(this, regionList);
            } else {
                if (regionBootstrap != null) {
                    regionBootstrap.disable();
                    regionBootstrap = null;
                }
            }

            Log.d(TAG, "done setupBeaconManager");
        }

        @Override
        public Context getApplicationContext() {
            return context;
        }

        @Override
        public void didEnterRegion(Region region) {
            Log.d(TAG, "didEnterRegion");
            if (!beaconEnabled) {
                Log.d(TAG, "skip enter region");
                return;
            }
            showNotification();
        }

        @Override
        public void didExitRegion(Region region) {
            Log.d(TAG, "didExitRegion");
        }

        @Override
        public void didDetermineStateForRegion(int i, Region region) {
            Log.d(TAG, "didDetermineStateForRegion");
        }

        private void showNotification() {
            if (beaconEnabled) {
                NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext());
                builder.setSmallIcon(config.getNotificationSmallIcon());

                builder.setAutoCancel(true);
                builder.setDefaults(Notification.DEFAULT_ALL);
                builder.setWhen(System.currentTimeMillis());
                builder.setColor(config.getNotificationColor()); //for Android 5.0 later
                builder.setTicker(config.getNotificationTicker());
                builder.setContentTitle(config.getNotificationContentTitle());
                builder.setContentText(config.getNotificationContentText());

                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setAction("android.intent.category.LAUNCHER");
                //see http://qiita.com/ueno-yuhei/items/46971ac51d1fa3315c8d
                intent.setClassName(config.getNotificationIntentPackageName(),
                        config.getNotificationIntentClassName());
                intent.setFlags(0x10200000);
                PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT);
                builder.setContentIntent(contentIntent);

                NotificationManagerCompat manager = NotificationManagerCompat.from(getApplicationContext());
                manager.notify(0, builder.build());
            }
        }

    }

    enum WaitState {
        WAIT_RESPONSE,
        WAIT_TIMER,
        WAIT_ONLINE,
    }

    class RetryTime {
        private int minute = 0;

        public RetryTime() {
        }

        public void reset() {
            this.minute = 0;
        }

        /**
         * next wait time.
         * 0->1->2-> ... Max 5 min
         * @return time(msec)
         */
        public int nextPeriod() {
            minute++;
            if (minute > 5) minute = 5;
            return 1000 * 60 * minute;
        }
    }

    class BeaconInfo {
        public String identifier;
        public String uuid;
        public String major;
        public String minor;

        public BeaconInfo() {
        }

    }
}
