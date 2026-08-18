package com.example.thermafl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ─── Telemetry Views ─────────────────────────────────────────────────
    private TextView tvTemperature;
    private TextView tvRam;
    private TextView tvBattery;
    private TextView tvModel;

    // ─── Alpha Indicator Views ───────────────────────────────────────────
    private TextView tvAlphaBadge;
    private ProgressBar progressAlpha;
    private TextView tvActiveBatch;
    private TextView tvActiveEpochs;
    private TextView tvActiveStatus;

    // ─── Training Progress Views ─────────────────────────────────────────
    private TextView tvStatus;
    private TextView tvMultiplier;
    private TextView tvRound;
    private TextView tvEpoch;
    private TextView tvLoss;
    private TextView tvAccuracy;

    // ─── Buttons ─────────────────────────────────────────────────────────
    private Button btnStartTraining;
    private Button btnSimulateHeat;

    // ─── State ───────────────────────────────────────────────────────────
    private boolean isTraining = false;
    private boolean isSimulatingHeat = false;
    private double currentAlpha = 1.0;

    private ThermaFLReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ─── Bind Views ─────────────────────────────────────────────────
        tvTemperature   = findViewById(R.id.tv_temperature);
        tvRam           = findViewById(R.id.tv_ram);
        tvBattery       = findViewById(R.id.tv_battery);
        tvModel         = findViewById(R.id.tv_model);

        tvAlphaBadge    = findViewById(R.id.tv_alpha_badge);
        progressAlpha   = findViewById(R.id.progress_alpha);
        tvActiveBatch   = findViewById(R.id.tv_active_batch);
        tvActiveEpochs  = findViewById(R.id.tv_active_epochs);
        tvActiveStatus  = findViewById(R.id.tv_active_status);

        tvStatus        = findViewById(R.id.tv_status);
        tvMultiplier    = findViewById(R.id.tv_multiplier);
        tvRound         = findViewById(R.id.tv_round);
        tvEpoch         = findViewById(R.id.tv_epoch);
        tvLoss          = findViewById(R.id.tv_loss);
        tvAccuracy      = findViewById(R.id.tv_accuracy);

        btnStartTraining = findViewById(R.id.btn_start_training);
        btnSimulateHeat  = findViewById(R.id.btn_simulate_heat);

        // ─── Start Training Button ──────────────────────────────────────
        btnStartTraining.setOnClickListener(v -> {
            if (isTraining) {
                stopTraining();
            } else {
                startTraining();
            }
        });

        // ─── Simulate Thermal Spike Button ──────────────────────────────
        btnSimulateHeat.setOnClickListener(v -> {
            isSimulatingHeat = !isSimulatingHeat;

            if (isSimulatingHeat) {
                btnSimulateHeat.setText("⬇ Stop Simulation");
                btnSimulateHeat.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#34A853")));
            } else {
                btnSimulateHeat.setText("\uD83C\uDF21\uFE0F Simulate 44°C");
                btnSimulateHeat.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#EA4335")));
            }

            Intent commandIntent = new Intent("TOGGLE_HEAT");
            commandIntent.setPackage(getPackageName());
            commandIntent.putExtra("simulate", isSimulatingHeat);
            sendBroadcast(commandIntent);
        });

        // ─── Start Telemetry Service ────────────────────────────────────
        Intent serviceIntent = new Intent(this, TelemetryService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        receiver = new ThermaFLReceiver();
    }

    /**
     * Start the TrainingService.
     */
    private void startTraining() {
        isTraining = true;
        btnStartTraining.setText("⏹  Stop Training");
        btnStartTraining.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#EA4335")));

        Intent intent = new Intent(this, TrainingService.class);
        intent.setAction("START_TRAINING");
        intent.putExtra("alpha", currentAlpha);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        tvStatus.setText("Status: Starting training...");
    }

    /**
     * Stop the TrainingService.
     */
    private void stopTraining() {
        isTraining = false;
        btnStartTraining.setText("▶  Start Training");
        btnStartTraining.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#0F9D58")));

        Intent intent = new Intent(this, TrainingService.class);
        intent.setAction("STOP_TRAINING");
        startService(intent);

        tvStatus.setText("Status: Idle");
    }

    /**
     * Update the alpha indicator UI (badge color, progress bar, status text).
     */
    private void updateAlphaUI(double alpha) {
        currentAlpha = alpha;

        // Update badge text
        if (tvAlphaBadge != null) {
            tvAlphaBadge.setText(String.format(Locale.US, "α = %.2f", alpha));

            // Color-code the badge based on alpha level
            int badgeColor;
            if (alpha >= 1.0) {
                badgeColor = Color.parseColor("#0F9D58");  // Green
            } else if (alpha >= 0.75) {
                badgeColor = Color.parseColor("#F9AB00");  // Amber
            } else if (alpha >= 0.5) {
                badgeColor = Color.parseColor("#E37400");  // Orange
            } else {
                badgeColor = Color.parseColor("#EA4335");  // Red
            }

            // Update badge background color
            GradientDrawable bg = (GradientDrawable) tvAlphaBadge.getBackground();
            if (bg != null) {
                bg.setColor(badgeColor);
            }

            // Update progress bar
            if (progressAlpha != null) {
                progressAlpha.setProgress((int) (alpha * 100));
                // Update progress bar color
                LayerDrawable drawable = (LayerDrawable) progressAlpha.getProgressDrawable();
                if (drawable != null) {
                    drawable.getDrawable(1).setColorFilter(badgeColor, PorterDuff.Mode.SRC_IN);
                }
            }
        }

        // Update active training params display
        int batchSize = Math.max(4, (int) (32 * alpha));
        int epochs = Math.max(1, (int) (4 * alpha));
        String statusText;

        if (alpha >= 0.75) {
            statusText = "Normal";
        } else if (alpha >= 0.5) {
            statusText = "Throttled";
        } else {
            statusText = "Heavy Throttle";
        }

        if (tvActiveBatch != null)  tvActiveBatch.setText("Batch: " + batchSize);
        if (tvActiveEpochs != null) tvActiveEpochs.setText("Epochs: " + epochs);
        if (tvActiveStatus != null) tvActiveStatus.setText("Mode: " + statusText);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("TELEMETRY_UPDATE");
        filter.addAction("SERVER_UPDATE");
        filter.addAction("TRAINING_UPDATE");
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    /**
     * Unified broadcast receiver for telemetry, server, and training updates.
     */
    private class ThermaFLReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("TELEMETRY_UPDATE".equals(action)) {
                // ─── Device telemetry from TelemetryService ─────────────
                float temp = intent.getFloatExtra("temp", 0.0f);
                long ram = intent.getLongExtra("ram", 0);
                int battery = intent.getIntExtra("battery", 0);
                String model = intent.getStringExtra("model");

                if (tvTemperature != null) {
                    String tempText = String.format(Locale.US, "🌡️ Temp: %.1f °C", temp);
                    tvTemperature.setText(tempText);
                    // Color-code temperature
                    if (temp > 40) {
                        tvTemperature.setTextColor(Color.parseColor("#EA4335"));
                    } else if (temp >= 30) {
                        tvTemperature.setTextColor(Color.parseColor("#E37400"));
                    } else {
                        tvTemperature.setTextColor(Color.parseColor("#3C4043"));
                    }
                }

                if (tvRam != null) {
                    tvRam.setText("💾 RAM: " + (ram >= 0 ? ram + " MB" : "N/A"));
                }

                if (tvBattery != null) {
                    String battText = "🔋 Battery: " + battery + "%";
                    tvBattery.setText(battText);
                    if (battery < 10) {
                        tvBattery.setTextColor(Color.parseColor("#EA4335"));
                    } else {
                        tvBattery.setTextColor(Color.parseColor("#3C4043"));
                    }
                }

                if (tvModel != null) {
                    tvModel.setText("Device: " + (model != null ? model : "Unknown"));
                }

            } else if ("SERVER_UPDATE".equals(action)) {
                // ─── Alpha & status from TelemetryService (Firebase) ────
                String status = intent.getStringExtra("status");
                double alpha = intent.getDoubleExtra("alpha", 1.0);

                Log.d(TAG, "UI received → Status: " + status + " | Alpha: " + alpha);

                updateAlphaUI(alpha);

                if (tvMultiplier != null) {
                    tvMultiplier.setText(String.format(Locale.US, "Model Multiplier: α = %.2f", alpha));
                }

                // Forward alpha update to TrainingService
                if (isTraining) {
                    Intent alphaIntent = new Intent("ALPHA_UPDATE");
                    alphaIntent.setPackage(getPackageName());
                    alphaIntent.putExtra("alpha", alpha);
                    alphaIntent.putExtra("status", status);
                    sendBroadcast(alphaIntent);
                }

            } else if ("TRAINING_UPDATE".equals(action)) {
                // ─── Training progress from TrainingService ─────────────
                int round = intent.getIntExtra("round", 0);
                int epoch = intent.getIntExtra("epoch", 0);
                float loss = intent.getFloatExtra("loss", 0f);
                float accuracy = intent.getFloatExtra("accuracy", 0f);
                int batchSize = intent.getIntExtra("batch_size", 32);
                int epochsPerRound = intent.getIntExtra("epochs_per_round", 4);
                double alpha = intent.getDoubleExtra("alpha", 1.0);
                boolean training = intent.getBooleanExtra("is_training", false);
                String message = intent.getStringExtra("message");

                if (tvRound != null) tvRound.setText(String.valueOf(round));
                if (tvEpoch != null) tvEpoch.setText(epoch + "/" + epochsPerRound);

                if (tvLoss != null) {
                    tvLoss.setText(String.format(Locale.US, "%.3f", loss));
                }

                if (tvAccuracy != null) {
                    tvAccuracy.setText(String.format(Locale.US, "%.1f%%", accuracy * 100));
                }

                if (message != null && tvStatus != null) {
                    tvStatus.setText("Status: " + message);
                }

                // Update alpha indicator from training data too
                updateAlphaUI(alpha);

                // Sync button state
                if (!training && isTraining) {
                    isTraining = false;
                    btnStartTraining.setText("▶  Start Training");
                    btnStartTraining.setBackgroundTintList(
                            ColorStateList.valueOf(Color.parseColor("#0F9D58")));
                }
            }
        }
    }
}