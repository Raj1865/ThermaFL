package com.example.thermafl;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class TelemetryService extends Service {

    private static final String CHANNEL_ID = "ThermaFL_Channel";
    private static final String TAG = "TelemetryService";

    private Handler handler;
    private Runnable telemetryRunnable;
    private DatabaseReference databaseReference;
    private DatabaseReference serverConfigRef; // FIX 3: Separate ref for server config
    private BroadcastReceiver commandReceiver;
    private ValueEventListener serverUpdateListener;

    private String deviceId;
    private boolean isSimulatingSpike = false;
    private String currentStatus = "Idle";
    private double currentAlpha = 1.0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ThermaFL Edge Node")
                .setContentText("Monitoring Hardware Telemetry...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        String androidId = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        deviceId = "EdgeNode_" + (androidId != null ? androidId : "unknown");

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
                Log.d(TAG, "Firebase manually initialized");
            } else {
                Log.d(TAG, "Firebase already initialized, skipping");
            }

            databaseReference = FirebaseDatabase
                    .getInstance("https://thermafl-default-rtdb.asia-southeast1.firebasedatabase.app/")
                    .getReference("EdgeDevices")
                    .child(deviceId);

            serverConfigRef = databaseReference.child("server_config");

            Log.d(TAG, "Firebase connected to: " + databaseReference.toString());
        } catch (Exception e) {
            Log.e(TAG, "Firebase Initialization Error: " + e.getMessage());
        }

        serverUpdateListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                // Self-heal: initialize server_config node if Python server hasn't written yet
                if (!snapshot.exists() || !snapshot.hasChild("alpha_multiplier") || !snapshot.hasChild("status")) {
                    Log.w(TAG, "server_config missing — writing defaults...");
                    Map<String, Object> defaults = new HashMap<>();
                    defaults.put("status", "Idle");
                    defaults.put("alpha_multiplier", 1.0);
                    if (serverConfigRef != null) {
                        serverConfigRef.updateChildren(defaults)
                                .addOnSuccessListener(aVoid -> Log.d(TAG, "server_config initialized"))
                                .addOnFailureListener(e -> Log.e(TAG, "server_config init failed: " + e.getMessage()));
                    }
                    return;
                }

                Object alphaObj = snapshot.child("alpha_multiplier").getValue();
                String status = snapshot.child("status").getValue(String.class);

                Log.d(TAG, "FIREBASE INBOUND -> Raw Alpha: " + alphaObj + " | Status: " + status);

                Intent serverIntent = new Intent("SERVER_UPDATE");
                serverIntent.setPackage(getPackageName());

                if (alphaObj != null) {
                    try {
                        double alpha = Double.parseDouble(alphaObj.toString());
                        currentAlpha = alpha;
                        serverIntent.putExtra("alpha", alpha);
                        Log.d(TAG, "BROADCASTING Alpha: " + alpha);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Could not parse alpha_multiplier: " + alphaObj);
                    }
                }

                if (status != null) {
                    currentStatus = status;
                    serverIntent.putExtra("status", status);
                }
                sendBroadcast(serverIntent);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to read server updates: " + error.getMessage());
            }
        };

        // FIX 2: Null check before attaching listener (guards against Firebase init
        // failure)
        if (serverConfigRef != null) {
            serverConfigRef.addValueEventListener(serverUpdateListener);
        } else {
            Log.e(TAG, "serverConfigRef is null — Firebase failed to initialize. Listener not attached.");
        }

        commandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("TOGGLE_HEAT".equals(intent.getAction())) {
                    isSimulatingSpike = intent.getBooleanExtra("simulate", false);
                    Log.d(TAG, "Thermal Simulation Mode: " + isSimulatingSpike);
                }
            }
        };

        ContextCompat.registerReceiver(
                this,
                commandReceiver,
                new IntentFilter("TOGGLE_HEAT"),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        handler = new Handler(Looper.getMainLooper());
        telemetryRunnable = new Runnable() {
            @Override
            public void run() {
                Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                float currentTemp = 0.0f;
                int batteryPct = 0;

                if (batteryStatus != null) {
                    int tempExtra = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                    currentTemp = tempExtra / 10.0f;

                    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        batteryPct = (int) ((level / (float) scale) * 100);
                    }
                }

                long availableRam = getAvailableRAM();
                String deviceModel = Build.MODEL;

                if (isSimulatingSpike) {
                    currentTemp = 44.5f;
                }

                Log.d(TAG, "Live Temp: " + currentTemp + "°C | Free RAM: " + availableRam + "MB");

                // FIX 3: Telemetry writes only to the root device node.
                // This no longer triggers the serverUpdateListener (which is on server_config).
                if (databaseReference != null) {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("device_model", deviceModel);
                    updates.put("battery_temp", currentTemp);
                    updates.put("battery_pct", batteryPct);
                    updates.put("free_ram_mb", availableRam);
                    updates.put("status", currentStatus);
                    updates.put("alpha_multiplier", currentAlpha);
                    updates.put("last_updated", System.currentTimeMillis());

                    databaseReference.updateChildren(updates)
                            .addOnSuccessListener(aVoid -> Log.d(TAG, "Firebase Sync Successful"))
                            .addOnFailureListener(e -> Log.e(TAG, "Firebase Sync Failed: " + e.getMessage()));
                }

                Intent uiIntent = new Intent("TELEMETRY_UPDATE");
                uiIntent.setPackage(getPackageName());
                uiIntent.putExtra("temp", currentTemp);
                uiIntent.putExtra("ram", availableRam);
                uiIntent.putExtra("battery", batteryPct);
                uiIntent.putExtra("model", deviceModel);
                sendBroadcast(uiIntent);

                handler.postDelayed(this, 5000);
            }
        };

        handler.post(telemetryRunnable);
    }

    private long getAvailableRAM() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            Log.w(TAG, "ActivityManager unavailable");
            return -1; // FIX 5: Return sentinel instead of misleading 0
        }
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / (1024 * 1024);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "ThermaFL Telemetry Service",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (handler != null) {
            handler.removeCallbacks(telemetryRunnable);
        }

        // FIX 2+3: Remove listener from serverConfigRef, not databaseReference
        if (serverConfigRef != null && serverUpdateListener != null) {
            serverConfigRef.removeEventListener(serverUpdateListener);
        }

        if (commandReceiver != null) {
            unregisterReceiver(commandReceiver);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}