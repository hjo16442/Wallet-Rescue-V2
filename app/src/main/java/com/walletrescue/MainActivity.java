package com.example.bitcoingen;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.params.MainNetParams;

public class MainActivity extends AppCompatActivity {

    private Button btnToggle;
    private TextView tvAddresses, tvStatus;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private long count = 0;
    private StringBuilder addressList = new StringBuilder();

    private final Runnable generateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                try {
                    // تولید کلید و آدرس SegWit
                    ECKey key = new ECKey();
                    SegwitAddress address = SegwitAddress.fromKey(MainNetParams.get(), key);
                    
                    count++;
                    addressList.insert(0, address.toString() + "\n"); // افزودن به ابتدای لیست
                    
                    // محدود کردن تعداد خطوط نمایش داده شده برای جلوگیری از پر شدن حافظه
                    if (addressList.length() > 5000) {
                        addressList.setLength(5000);
                    }

                    // بروزرسانی UI هر 100 آدرس یکبار برای عملکرد بهتر
                    if (count % 100 == 0) {
                        runOnUiThread(() -> {
                            tvAddresses.setText(addressList.toString());
                            tvStatus.setText("در حال تولید... | تعداد: " + count);
                        });
                    }
                    
                    // تکرار سریع
                    handler.post(this);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.btnToggle);
        tvAddresses = findViewById(R.id.tvAddresses);
        tvStatus = findViewById(R.id.tvStatus);

        btnToggle.setOnClickListener(v -> toggleGeneration());
    }

    private void toggleGeneration() {
        if (isRunning) {
            isRunning = false;
            btnToggle.setText("شروع تولید");
            btnToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_blue_light));
            tvStatus.setText("وضعیت: متوقف");
        } else {
            isRunning = true;
            btnToggle.setText("توقف");
            btnToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_red_light));
            handler.post(generateRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.removeCallbacks(generateRunnable);
    }
}
