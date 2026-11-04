package com.walletrescue;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText inputText;
    private TextView outputText, statusText, speedText;
    private CheckBox forceBox;
    private Button startBtn, stopBtn;
    private ScrollView outputScroll;
    private MnemonicRecovery engine;
    private long t0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText    = findViewById(R.id.inputText);
        outputText   = findViewById(R.id.outputText);
        statusText   = findViewById(R.id.statusText);
        speedText    = findViewById(R.id.speedText);
        forceBox     = findViewById(R.id.forceBox);
        startBtn     = findViewById(R.id.startBtn);
        stopBtn      = findViewById(R.id.stopBtn);
        outputScroll = findViewById(R.id.outputScroll);

        stopBtn.setEnabled(false);
        inputText.setText("? ? ? ? ? ? ? ? ? ? ? ?");

        try {
            engine = new MnemonicRecovery(this);
        } catch (Exception e) {
            statusText.setText("خطا در بارگذاری دیکشنری: " + e.getMessage());
            startBtn.setEnabled(false);
            return;
        }

        startBtn.setOnClickListener(v -> onStartClicked());
        stopBtn.setOnClickListener(v -> onStopClicked());
    }

    private void onStartClicked() {
        String phrase = inputText.getText().toString();
        int wordCount = phrase.trim().isEmpty() ? 12 : phrase.trim().split("\\s+").length;
        int missingCount = 0;
        for (String w : phrase.trim().split("\\s+")) if (w.equals("?")) missingCount++;
        if (phrase.trim().isEmpty()) missingCount = 12;

        if (missingCount == wordCount && wordCount > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("هشدار جدی")
                    .setMessage("همهٔ " + wordCount + " کلمه گم شده‌اند.\n\n" +
                            "این عملیات از نظر ریاضی هرگز تمام نمی‌شود " +
                            "(2^" + (wordCount == 12 ? 128 : 256) + " حالت).\n\n" +
                            "فقط برای تست اجرا کنید. مطمئن هستید؟")
                    .setPositiveButton("بله، اجرا کن", (d, w) -> beginSearch(phrase, true))
                    .setNegativeButton("لغو", null)
                    .show();
            return;
        }

        beginSearch(phrase, forceBox.isChecked());
    }

    private void beginSearch(String phrase, boolean force) {
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        outputText.setText("");
        statusText.setText("در حال شروع...");
        speedText.setText(" ");
        t0 = SystemClock.elapsedRealtime();

        engine.findAsync(phrase, force, new MnemonicRecovery.Callback() {
            @Override
            public void onProgress(long tested, long found) {
                long elapsed = SystemClock.elapsedRealtime() - t0;
                long speed = elapsed > 0 ? tested * 1000 / elapsed : 0;
                runOnUiThread(() -> {
                    statusText.setText("تست: " + fmt(tested) + " | یافته: " + found);
                    speedText.setText("سرعت: " + fmt(speed) + " تست/ثانیه");
                });
            }

            @Override
            public void onResult(List<String> found) {
                runOnUiThread(() -> {
                    if (found.isEmpty()) {
                        outputText.setText("نتیجه‌ای یافت نشد.\n\nتست‌شده: " + fmt(engine.getTested()));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < found.size(); i++) {
                            sb.append(i + 1).append(". ").append(found.get(i)).append("\n\n");
                        }
                        outputText.setText(sb.toString());
                        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_UP));
                    }
                    statusText.setText("تمام شد — " + found.size() + " نتیجه");
                    speedText.setText(" ");
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    statusText.setText("خطا");
                    outputText.setText("خطا: " + message);
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }
        });
    }

    private void onStopClicked() {
        engine.stop();
        statusText.setText("متوقف شد — تست‌شده: " + fmt(engine.getTested()));
        speedText.setText(" ");
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
    }

    private static String fmt(long v) {
        return String.format(Locale.US, "%,d", v);
    }
}
