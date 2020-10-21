package com.socketservice;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleService;
import android.arch.lifecycle.Observer;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;


import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;

import fr.naxxum.alumni.BuildConfig;
import fr.naxxum.alumni.MainActivity;
import fr.naxxum.alumni.R;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class NotificationSocketService extends LifecycleService {

    private static final String ACTION_START = "drivinglog.service.start";
    private static final String ACTION_STOP = "drivinglog.service.stop";

    private static final String INTENT_EXTRA_CONNECT_URL = "connect_url";
    private static final String INTENT_EXTRA_USER_ID = "user_id";

    private static final String PREF_ACTION = "pref_action";
    private static final String PREF_CONNECT_URL = "pref_connect_url";
    private static final String PREF_USER_ID = "pref_user_id";

    private static final String PREFS_NAME = "NotificationSocketServiceFile";

    private static final String PREF_ACTION_DEFAULT = ACTION_START;
    private static final String PREF_CONNECT_URL_DEFAULT = "";

    private static final String BROADCAST_INCOMING_EVENT = "incoming.event";
    private static final String BROADCAST_OUTGOING_EVENT = "outgoing.event";

    public static final String MAIN_APP_STATE_CHANGE_EVENT = "state.event";
    public static final String ALERT_DISABLED_EVENT = "alert.event";

    public static Boolean userSocketAuthorized = false;
    private static Socket socket;

    private static String host;
    private static Integer port;

    private static String appName = "Driver App";
    private static String packageName = "com.mapon.mapongo";
    private static PackageManager pm = null;

    private String connectUrl;
    private String userId;

    private static PowerManager.WakeLock wakeLock;
    private static final Object LOCK = NotificationSocketService.class;

    private boolean mRunning = false;

    protected boolean mMainAppRunningForeground = true;
    protected boolean mMainAppShowAlerts = true;

    private BroadcastReceiver notificationReceiver = null;
    private Observer<Intent> obs = null;

    public static void start(Context context, String connectUrl,String userId) {
        Log.d("NotificationService", "NotificationSocketService STARTED");

        // remove all notifications from status bar. This method gets called when app is started
        NotificationManager nMgr = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nMgr.cancelAll();

        Intent intent = new Intent(context, NotificationSocketService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(INTENT_EXTRA_CONNECT_URL, connectUrl);
        intent.putExtra(INTENT_EXTRA_USER_ID, userId);
        context.startService(intent);
    }

    public static void stop(Context context) {
        Log.d("NotificationService", "NotificationSocketService STOPPED");

        Intent intent = new Intent(context, NotificationSocketService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("NotificationService", "onStartCommand METHOD");
        Log.d("NotificationService", "Connected salah");

        String action;
        if (intent != null && intent.getAction() != null) {
            String intentConnectUrl = intent.getStringExtra(INTENT_EXTRA_CONNECT_URL);
            String intentUserId = intent.getStringExtra(INTENT_EXTRA_USER_ID);

            SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putString(PREF_ACTION, intent.getAction());
            editor.putString(PREF_CONNECT_URL,intentConnectUrl );
            editor.putString(PREF_USER_ID,intentUserId );
            editor.apply();

            action = intent.getAction();
            this.connectUrl = intentConnectUrl;
            this.userId = intentUserId;
        } else {
            SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
            action = settings.getString(PREF_ACTION, PREF_ACTION_DEFAULT);
            this.connectUrl = settings.getString(PREF_CONNECT_URL, PREF_CONNECT_URL_DEFAULT);
            this.userId = settings.getString(PREF_USER_ID, PREF_CONNECT_URL_DEFAULT);
        }

        if (action.equals(ACTION_START)) {
            mRunning = true;
            connect();
            listenBroadcasts();

            appName = getApplicationName();
            packageName = getPackageName();
            pm = getPackageManager();

            String channelId = appName.replaceAll(" ", "_") + "_channel";
            String channelName = appName.replaceAll(" ", "_") + "_name";
            NotificationManager nManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel mChannel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW);
                nManager.createNotificationChannel(mChannel);
            }

            Intent notificationIntent = pm.getLaunchIntentForPackage(packageName);

            Notification notification = new NotificationCompat.Builder(this)
                    .setChannelId(channelId)
                    .setContentTitle(appName)
                    .setContentText("Service running")
                    .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0,
                        notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                    .setColor(Color.parseColor("#98ca02"))
                    .setPriority(Notification.PRIORITY_MIN)
                    .setVibrate(null)
                    .setSound(null)
                    .setOngoing(true).build();

            startForeground(101, notification);
        } else if (action.equals(ACTION_STOP)) {
            if (mRunning) {
                SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString(PREF_USER_ID,"" );
                this.userId = "";
                disconnect();
                stopListeningBroadcasts();
                stopForeground(true);
                stopSelf();
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void connect() {
        Log.d("NotificationService", "connect METHOD");
        try {
            if (socket == null) {
                IO.Options options = new IO.Options();
                options.reconnection = true;
                options.forceNew = true;
                //options.transports = new String[] {"websocket", "polling"};
                socket = IO.socket(this.connectUrl, options);

                Log.d("NotificationService", "SOCKET OPENED WITH URL: " + this.connectUrl);
                try {
                    JSONObject jsonData = new JSONObject("{\"roomId\": \"user-"+this.userId +"\"}");
                    socket.emit("join",jsonData);

                } catch (JSONException e) {
                    e.printStackTrace();
                }


                socket.on(Socket.EVENT_RECONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        Log.d("NotificationService", "EVENT_RECONNECT");
                        Log.d("NotificationService", "Emit getUndelivered");
                        socket.emit("getUndelivered");
                        JSONObject jsonData = null;
                        try {
                            jsonData = new JSONObject("{\"roomId\": \"user-"+userId +"\"}");
                            socket.emit("join",jsonData);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                    }
                });
                socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        connectionEstablishedEvent();
                    }
                });
                socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        userSocketAuthorized = false;
                        Log.d("NotificationService", "EVENT_DISCONNECT");
                    }
                });
                socket.on("user_connected", new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        userSocketAuthorized = true;
                        try {
                            Log.d("NotificationService", "EVENT_USER_CONNECTED");

                            JSONObject payload = new JSONObject();
                            payload.put("type", "user_connected");
                            broadcastEvent("user_connected", payload);

                            wakeUp();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
                socket.on("callPending", new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        try {
                            Log.d("NotificationService", "EVENT_CALL_PENDING");
                            Log.d("NotificationService",args[0].toString());
                            JSONObject payload = new JSONObject();
                            payload.put("type", "callPending");
                            broadcastEvent("callPending", payload);


                            Intent alertIntent;
                            alertIntent = new Intent(getApplicationContext(), MainActivity.class);
                            alertIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT|Intent.FLAG_ACTIVITY_NEW_TASK| Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            if(args[0] != null){
                                alertIntent.putExtra("ALUMNI_CALL",args[0].toString());
                            }
                            Log.d("NotificationService", "EVENT_CALL_PENDING");
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                NotificationManager notificationManager =
                                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

                                AudioAttributes attributes = new AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .build();

                                String CHANNEL_ID = BuildConfig.APPLICATION_ID.concat("_notification_id");
                                String CHANNEL_NAME = BuildConfig.APPLICATION_ID.concat("_notification_name");
                                assert notificationManager != null;

                                NotificationChannel mChannel = notificationManager.getNotificationChannel(CHANNEL_ID);
                                if (mChannel == null) {
                                    mChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                                    notificationManager.createNotificationChannel(mChannel);
                                }
                                Intent fullScreenIntent = new Intent(getApplicationContext(), MainActivity.class);
                                if(args[0] != null){
                                    fullScreenIntent.putExtra("ALUMNI_CALL",args[0].toString());
                                }
                                fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT|Intent.FLAG_ACTIVITY_NEW_TASK| Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                 PendingIntent  p = PendingIntent
                                        .getActivity(getApplicationContext(), 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                                NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID);
                                long[] vibs = {0, 250, 250, 250,250,250,250};
                                builder.setSmallIcon(R.mipmap.ic_launcher)
                                        .setContentTitle(getString(R.string.app_name))
                                        .setContentText("You have a pending call!")
                                        .setColor(0x2dd36f)
                                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                                        .setCategory(NotificationCompat.CATEGORY_CALL)
                                        .setContentIntent(p)
                                        .setAutoCancel(true)
                                        .setVibrate(vibs)
                                        .setOngoing(true);

                                Notification notification = builder.build();
                                notificationManager.notify(123, notification);
                            } else {
                                startActivity(alertIntent);
                            }




                            wakeUp();

                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
                socket.on("heartbeat_ping", new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        Log.d("NotificationService", "EVENT_PONG");
                        socket.emit("heartbeat_pong");
                    }
                });
                socket.on("messaging", new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        try {
                            Log.d("NotificationService", "EVENT INCOMING DATA");
                            if (args.length > 0) {
                                Ack ack = (Ack) args[args.length - 1];
                                if (ack != null) {
                                    ack.call();
                                }
                            }
                            JSONObject eventData = new JSONObject(args[0].toString());
                            String event = eventData.getString("type");

                            Log.d("NotificationService", "EVENT " + event);
                            Log.d("NotificationService", "PAYLOAD " + eventData.toString());

                            if (isMainAppForeground()) {
                                Log.d("NotificationService", "APP RUNNING FOREGROUND, BROADCASTING EVENT");
                                broadcastEvent(event, eventData);
                            } else {
                                boolean alert = !eventData.isNull("alert");

                                if (alert) {
                                    Language language = new Language(getApplicationContext());
                                    JSONObject alertData = eventData.getJSONObject("alert");
                                    JSONObject placeholders = alertData.getJSONObject("titlePlaceholders");
                                    String alertMessage, notificationMessage;
                                    alertMessage = notificationMessage = language.getLanguageEntry(alertData.getString("title"), placeholders);

                                    Log.d("NotificationService", "APP RUNNING BACKGROUND, SHOWING NOTIFICATION AND ALERT AND BROADCASTING");
                                    showNotification(event, eventData, notificationMessage, mMainAppShowAlerts);

                                    if (mMainAppShowAlerts) {
                                        showAlert(event, eventData, alertMessage);
                                    }
                                }
                            }

                            wakeUp();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
                socket.on("data", new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        try {
                            Log.d("NotificationService", "EVENT INCOMING DATA");

                            if (args.length > 0) {
                                Ack ack = (Ack) args[args.length - 1];
                                if (ack != null) {
                                    ack.call();
                                }
                            }
                            Log.d("NotificationService", "EVENT INCOMING DATA"+args[0].toString());

                            JSONObject eventData = new JSONObject(args[0].toString());
                            String event = eventData.getString("event");
                            JSONObject payload = eventData.getJSONObject("data");
                            boolean alert = payload.optBoolean("alert", false);
                            String alertMessage = null;
                            String notificationMessage = null;
                            if (alert) {
                                alertMessage = notificationMessage = payload.optString("alertMessage", "empty message");
                            }
                            Log.d("NotificationService", "EVENT " + event);
                            Log.d("NotificationService", "PAYLOAD " + payload.toString());

                            broadcastEvent(event, payload);

                            if (!isMainAppForeground()) {
                                if (alert) {
                                    boolean undeliveredEvent = payload.optBoolean("unsent", false);
                                    if (undeliveredEvent) {
                                        Language language = new Language(getApplicationContext());
                                        String undeliveredMessage = "";
                                        try {
                                            undeliveredMessage = "+" + payload.getJSONObject("dashboard").get("messages").toString() + " " + language.getLanguageEntry("new_undelivered_messages");
                                        } catch (JSONException e) {
                                            Log.d("NotificationService", "NO ADDITIONAL MESSAGES");
                                        }

                                        try {
                                            undeliveredMessage += "\n+" + payload.getJSONObject("dashboard").get("tasks").toString() + " " + language.getLanguageEntry("new_undelivered_tasks");
                                        } catch (JSONException e) {
                                            Log.d("NotificationService", "NO ADDITIONAL TASKS");
                                        }

                                        notificationMessage = undeliveredMessage;
                                    }

                                    Log.d("NotificationService", "APP RUNNING BACKGROUND, SHOWING NOTIFICATION AND ALERT AND BROADCASTING");
                                    showNotification(event, payload, notificationMessage, mMainAppShowAlerts);

                                    if (mMainAppShowAlerts) {
                                        showAlert(event, payload, alertMessage);
                                    }
                                }
                            }

                            wakeUp();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
                socket.connect();
            } else {
                connectionEstablishedEvent();
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void connectionEstablishedEvent() {
        try {
            Log.d("NotificationService", "EVENT_CONNECT");

            JSONObject payload = new JSONObject();
            payload.put("type", Socket.EVENT_CONNECT);
            broadcastEvent("socket_event", payload);

            wakeUp();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private boolean isMainAppForeground() {
        boolean isMainAppForeground = false;
        KeyguardManager km = (KeyguardManager) getApplicationContext().getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);

        boolean isPhoneLocked = km.inKeyguardRestrictedInputMode();
        boolean isSceenAwake = (Build.VERSION.SDK_INT < 20 ? pm.isScreenOn() : pm.isInteractive());

        List<ActivityManager.RunningAppProcessInfo> runningProcessInfo = activityManager.getRunningAppProcesses();
        if (runningProcessInfo != null && AlertActivity.isAlertShown == 0) {
            for (ActivityManager.RunningAppProcessInfo appProcess : runningProcessInfo) {
                Log.d("NotificationService", String.valueOf(appProcess.importance));
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        && appProcess.processName.equals(getApplication().getPackageName())
                            && !isPhoneLocked && isSceenAwake) {
                    isMainAppForeground = true;
                    break;
                }
            }
        }

        return isMainAppForeground;
    }

    private void listenBroadcasts() {
        stopListeningBroadcasts();
        notificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    Log.d("NotificationService", "EMIT EVENT: " + intent.getExtras().getString("userdata","{}"));

                    JSONObject eventData = new JSONObject(intent.getExtras().getString("userdata","{}"));

                    Log.d("NotificationService", "EMIT EVENT: " + eventData.toString());

                    if (socket != null) {
                        socket.emit("data", eventData);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        obs = (intent)->{
            notificationReceiver.onReceive(this.getApplicationContext(),intent);
        };
        LocalBroadcastManager.getInstance().observe(this,obs);

        final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isBackground = intent.getBooleanExtra("isBackground", false);

                mMainAppRunningForeground = !isBackground;

                Log.d("NotificationService", "isBackground EVENT: " + String.valueOf(isBackground));
            }
        };
        Observer<Intent> obs2 = (intent)->{
            stateReceiver.onReceive(this,intent);
        };

        LocalBroadcastManager.getInstance().observe((LifecycleOwner) this,obs2);

        final BroadcastReceiver alertDisabledReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isAlertsDisabled = intent.getBooleanExtra("isDisabled", true);

                mMainAppShowAlerts = !isAlertsDisabled;

                Log.d("NotificationService", "isBackground EVENT: " + String.valueOf(isAlertsDisabled));
            }
        };
        Observer<Intent> obs3 = (intent)->{
            alertDisabledReceiver.onReceive(this,intent);
        };
        LocalBroadcastManager.getInstance().observe((LifecycleOwner) this,obs3);
    }

    private void stopListeningBroadcasts() {
        if (notificationReceiver != null) {
            LocalBroadcastManager.getInstance().removeObserver(obs);
        }
    }

    private void disconnect() {
        Log.d("NotificationService", "disconnect METHOD");

        if (socket != null) {
            socket.disconnect();
        }
        socket = null;
    }

    private void broadcastEvent(String event, JSONObject payload) {
        final Intent intent = new Intent(BROADCAST_INCOMING_EVENT);
        Bundle b = new Bundle();
        b.putString("userdata",
                "{" +
                        "event: '" + event + "'," +
                        "data: " + payload.toString() +
                        "}");
        intent.putExtras(b);
        LocalBroadcastManager.getInstance().postValue(intent);
    }

    private void showAlert(String event, JSONObject payload, String alertMessage) {
        Intent alertIntent;
        alertIntent = new Intent(getApplicationContext(), AlertActivity.class);

        alertIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        alertIntent.putExtra("event", event);
        alertIntent.putExtra("data", payload.toString());
        alertIntent.putExtra("alertMessage", alertMessage);
        startActivity(alertIntent);
    }

    private void showNotification(String event, JSONObject payload, String alertMessage, boolean regularPriority) {
        Language language = new Language(getApplicationContext());
        String messageTitle = language.getLanguageEntry("new_notification");
        String customMessageTitle = language.getLanguageEntry("new_" + event);
        if ((customMessageTitle != null) && (customMessageTitle.length() > 0)) {
            messageTitle = customMessageTitle;
        }

        int notifPriority = (Build.VERSION.SDK_INT < 26 ? Notification.PRIORITY_DEFAULT : NotificationManager.IMPORTANCE_DEFAULT);

        if (!regularPriority) {
            notifPriority = (Build.VERSION.SDK_INT < 26 ? Notification.PRIORITY_HIGH : NotificationManager.IMPORTANCE_HIGH);
        }

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), appName.replaceAll(" ", "_") + "_channel");
        builder
                .setWhen(System.currentTimeMillis()).setAutoCancel(true)
                .setContentTitle(messageTitle)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentText(alertMessage)
                .setPriority(notifPriority);

        Intent notificationIntent = pm.getLaunchIntentForPackage(packageName);

        notificationIntent.putExtra("event", event);
        notificationIntent.putExtra("data", payload.toString());

        PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        builder.setContentIntent(contentIntent);
        Notification n = builder.build();
        nm.notify((int) ((new Date().getTime() / 1000L) % Integer.MAX_VALUE), n);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d("NotificationService", "onTaskRemoved METHOD");
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        PendingIntent restartServicePI = PendingIntent.getService(
                getApplicationContext(), 1, restartService,
                PendingIntent.FLAG_ONE_SHOT);
        AlarmManager alarmService = (AlarmManager)getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() +1000, restartServicePI);
    }

    private void wakeUp(){
        synchronized(LOCK) {
            if (wakeLock == null) {
                Log.d("NotificationService", "wakeUp wakeLock");
                PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK  | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "alumni:wakelock");
            }else{
                Log.d("NotificationService", "There is a lock Acquired");

            }

        }
    }

    public String getApplicationName() {
        ApplicationInfo applicationInfo = getApplicationContext().getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : getApplicationContext().getString(stringId);
    }

    private PendingIntent openScreen(int notificationId) {
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT|Intent.FLAG_ACTIVITY_NEW_TASK| Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
