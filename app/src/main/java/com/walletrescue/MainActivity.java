package com.walletrescue;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.bitcoinj.crypto.MnemonicCode;

import java.security.SecureRandom;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private final SecureRandom secureRandom = new SecureRandom();
    private TextView phraseText;
    private TextView statusText;
    private RadioGroup lengthGroup;
    private Button generateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        phraseText = findViewById(R.id.phraseText);
        statusText = findViewById(R.id.statusText);
        lengthGroup = findViewById(R.id.lengthGroup);
        generateButton = findViewById(R.id.generateButton);

        generateButton.setOnClickListener(v -> generatePhrase());
    }

    private void generatePhrase() {
        generateButton.setEnabled(false);
        try {
            int entropyBytes = lengthGroup.getCheckedRadioButtonId() == R.id.words24 ? 32 : 16;
            byte[] entropy = new byte[entropyBytes];
            secureRandom.nextBytes(entropy);

            List<String> words = MnemonicCode.INSTANCE.toMnemonic(entropy);
            StringBuilder phrase = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) phrase.append("   ");
                phrase.append(i + 1).append(". ").append(words.get(i));
            }

            phraseText.setText(phrase.toString());
            statusText.setText("عبارت معتبر BIP39 ساخته شد. این عبارت فقط یک‌بار نمایش داده می‌شود.");
            java.util.Arrays.fill(entropy, (byte) 0);
        } catch (Exception e) {
            phraseText.setText("");
            statusText.setText("ساخت عبارت ناموفق بود. دوباره تلاش کنید.");
        } finally {
            generateButton.setEnabled(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (phraseText != null) phraseText.setText("");
        super.onDestroy();
    }
}
