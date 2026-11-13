package com.example.auto_mnemonic;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.bitcoinj.core.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import java.security.SecureRandom;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvResult;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private int wordCount = 12; // پیش‌فرض ۱۲ کلمه

    // Runnable برای اجرای مداوم
    private final Runnable generateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                generateAndDisplay();
                // تکرار هر ۵۰۰ میلی‌ثانیه (نیم ثانیه)
                handler.postDelayed(this, 500); 
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvResult = findViewById(R.id.tvResult);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startAutoGeneration());
        btnStop.setOnClickListener(v -> stopAutoGeneration());
    }

    private void startAutoGeneration() {
        if (!isRunning) {
            isRunning = true;
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            handler.post(generateRunnable);
        }
    }

    private void stopAutoGeneration() {
        isRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        handler.removeCallbacks(generateRunnable);
    }

    private void generateAndDisplay() {
        try {
            // انتخاب تصادفی بین ۱۲ و ۲۴ کلمه یا ثابت نگه داشتن آن
            int entropyBits = (wordCount == 12) ? 128 : 256;
            
            byte[] entropy = new byte[entropyBits / 8];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(entropy);

            List<String> mnemonic = MnemonicCode.INSTANCE.toMnemonic(entropy);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mnemonic.size(); i++) {
                sb.append(mnemonic.get(i));
                if (i < mnemonic.size() - 1) sb.append(" ");
            }
            
            // نمایش در UI
            tvResult.setText(sb.toString());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoGeneration(); // توقف هنگام بسته شدن برنامه
    }
}
