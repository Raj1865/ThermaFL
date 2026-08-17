package com.example.thermafl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView tvTemperature;
    private TextView tvRam;
    private TextView tvBattery;
    private TextView tvModel;
    private TextView tvStatus;
    private TextView tvMultiplier;
    private TelemetryReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTemperature = findViewById(R.id.tv_temperature);
        tvRam = findViewById(R.id.tv_ram);
        tvBattery = findViewById(R.id.tv_battery);
        tvModel = findViewById(R.id.tv_model);
        tvMultiplier = findViewById(R.id.tv_multiplier);
        tvStatus = findViewById(R.id.tv_status);

        Button btnSimulate = findViewById(R.id.btn_simulate_heat);
        btnSimulate.setOnClickListener(v -> {
            boolean isCurrentlySimulating = btnSimulate.getText().toString().startsWith("Stop");

            if (isCurrentlySimulating) {
                btnSimulate.setText("Simulate Thermal Spike (44°C)");
                // FIX 4: Material buttons use backgroundTintList, not setBackgroundColor
                btnSimulate.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#EA4335")));
            } else {
                btnSimulate.setText("Stop Thermal Simulation");
                btnSimulate.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#34A853")));
            }

            Intent commandIntent = new Intent("TOGGLE_HEAT");
            commandIntent.setPackage(getPackageName());
            commandIntent.putExtra("simulate", !isCurrentlySimulating);
            sendBroadcast(commandIntent);
        });

        Intent serviceIntent = new Intent(this, TelemetryService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        receiver = new TelemetryReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("TELEMETRY_UPDATE");
        filter.addAction("SERVER_UPDATE");
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    private class TelemetryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("TELEMETRY_UPDATE".equals(action)) {
                float temp = intent.getFloatExtra("temp", 0.0f);
                long ram = intent.getLongExtra("ram", 0);
                int battery = intent.getIntExtra("battery", 0);
                String model = intent.getStringExtra("model");

                // FIX 6: Consistent null checks across all views
                if (tvTemperature != null) tvTemperature.setText("Battery Temp: " + temp + " °C");
                if (tvRam != null) tvRam.setText("Available RAM: " + (ram >= 0 ? ram + " MB" : "N/A"));
                if (tvBattery != null) tvBattery.setText("Battery Level: " + battery + "%");
                if (tvModel != null) tvModel.setText("Device: " + (model != null ? model : "Unknown"));
            } else if ("SERVER_UPDATE".equals(action)) {
                String status = intent.getStringExtra("status");
                double alpha = intent.getDoubleExtra("alpha", 1.0);

                Log.d("MainActivity", "UI RECEIVED -> Status: " + status + " | Alpha: " + alpha);

                if (status != null && tvStatus != null) tvStatus.setText("Status: " + status);
                if (tvMultiplier != null) tvMultiplier.setText("Model Multiplier: α = " + alpha);
            }
        }
    }
}