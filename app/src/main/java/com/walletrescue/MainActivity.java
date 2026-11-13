package com.walletrescue;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.bitcoinj.crypto.MnemonicCode;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int CREATE_FILE = 41;
    private static final int MAX_PHRASES_PER_RUN = 10;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom secureRandom = new SecureRandom();
    private final List<String> generated = new ArrayList<>();
    private TextView outputText, statusText, counterText;
    private EditText countInput;
    private Button startBtn, stopBtn, saveBtn;
    private RadioGroup lengthGroup;
    private boolean running = false;
    private int targetCount = 1;
    private int completed = 0;
    private int wordCount = 12;

    private final Runnable generatorTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                byte[] entropy = new byte[wordCount == 12 ? 16 : 32];
                secureRandom.nextBytes(entropy);
                String phrase = String.join(" ", MnemonicCode.INSTANCE.toMnemonic(entropy));
                generated.add(phrase);
                completed++;
                outputText.setText("عبارت شماره " + completed + " از " + targetCount + "\n\n" + phrase);
                counterText.setText("تعداد تولیدشده: " + completed + " / " + targetCount);
                statusText.setText("تولید موفق — عبارت فقط روی همین گوشی ساخته شد");
                ((ScrollView)findViewById(R.id.outputScroll)).post(() ->
                        ((ScrollView)findViewById(R.id.outputScroll)).fullScroll(ScrollView.FOCUS_DOWN));
                if (completed >= targetCount) {
                    stopGeneration("پایان تولید");
                } else {
                    handler.postDelayed(this, 1200);
                }
            } catch (Exception e) {
                stopGeneration("خطا در تولید: " + e.getMessage());
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        outputText = findViewById(R.id.outputText);
        statusText = findViewById(R.id.statusText);
        counterText = findViewById(R.id.counterText);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        saveBtn = findViewById(R.id.saveBtn);
        countInput = findViewById(R.id.countInput);
        lengthGroup = findViewById(R.id.lengthGroup);

        startBtn.setOnClickListener(v -> startGeneration());
        stopBtn.setOnClickListener(v -> stopGeneration("تولید متوقف شد"));
        saveBtn.setOnClickListener(v -> saveToTxt());
        updateButtons();
    }

    private void startGeneration() {
        if (running) return;
        int checked = lengthGroup.getCheckedRadioButtonId();
        wordCount = checked == R.id.radio24 ? 24 : 12;
        try {
            targetCount = Integer.parseInt(countInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            targetCount = 1;
        }
        if (targetCount < 1 || targetCount > MAX_PHRASES_PER_RUN) {
            Toast.makeText(this, "تعداد باید بین ۱ تا ۱۰ باشد", Toast.LENGTH_LONG).show();
            return;
        }
        completed = 0;
        generated.clear();
        outputText.setText("");
        counterText.setText("تعداد تولیدشده: 0 / " + targetCount);
        running = true;
        statusText.setText("در حال تولید عبارت استاندارد BIP39...");
        updateButtons();
        handler.post(generatorTask);
    }

    private void stopGeneration(String message) {
        running = false;
        handler.removeCallbacks(generatorTask);
        statusText.setText(message);
        updateButtons();
    }

    private void updateButtons() {
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        saveBtn.setEnabled(!generated.isEmpty() && !running);
    }

    private void saveToTxt() {
        if (generated.isEmpty()) {
            Toast.makeText(this, "ابتدا یک عبارت تولید کنید", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "wallet-rescue-bip39.txt");
        startActivityForResult(intent, CREATE_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IllegalStateException("امکان نوشتن فایل وجود ندارد");
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < generated.size(); i++) {
                    text.append("عبارت ").append(i + 1).append(":\n")
                            .append(generated.get(i)).append("\n\n");
                }
                stream.write(text.toString().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "فایل ذخیره شد؛ آن را محرمانه نگه دارید", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "خطا در ذخیره فایل: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onDestroy() {
        stopGeneration("برنامه بسته شد");
        super.onDestroy();
    }
}