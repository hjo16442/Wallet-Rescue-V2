package com.example.bitcoingen;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.params.MainNetParams;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private Button btnToggle;
    private TextView tvAddresses, tvStatus, tvFound;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private long count = 0;
    private long foundCount = 0;
    private StringBuilder addressList = new StringBuilder();
    private StringBuilder foundList = new StringBuilder();
    
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable generateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                try {
                    // ۱. تولید آنتروپی و عبارت بازیابی
                    byte[] entropy = new byte[16];
                    new SecureRandom().nextBytes(entropy);
                    List<String> mnemonicList = MnemonicCode.INSTANCE.toMnemonic(entropy);
                    String mnemonicPhrase = String.join(" ", mnemonicList);

                    // ۲. ساخت کلید اصلی و مشتق‌سازی
                    byte[] seedBytes = MnemonicCode.INSTANCE.toSeed(mnemonicList, "");
                    DeterministicKey masterKey = HDKeyDerivation.createMasterPrivateKey(seedBytes);
                    
                    List<ChildNumber> path = Arrays.asList(
                            new ChildNumber(84, true),
                            new ChildNumber(0, true),
                            new ChildNumber(0, true),
                            new ChildNumber(0, false),
                            new ChildNumber(0, false)
                    );

                    DeterministicKey childKey = masterKey;
                    for (ChildNumber childNumber : path) {
                        childKey = HDKeyDerivation.deriveChildKey(childKey, childNumber);
                    }

                    // ۳. تولید آدرس
                    SegwitAddress address = SegwitAddress.fromKey(MainNetParams.get(), childKey);
                    String addressStr = address.toBech32();

                    count++;

                    // ۴. چک کردن موجودی در Thread جداگانه
                    executor.execute(() -> {
                        try {
                            long balance = checkBalance(addressStr);
                            
                            if (balance > 0) {
                                foundCount++;
                                String foundEntry = "🎉 آدرس با موجودی پیدا شد!\n" +
                                        "آدرس: " + addressStr + "\n" +
                                        "موجودی: " + balance + " ساتوشی\n" +
                                        "عبارت: " + mnemonicPhrase + "\n\n";
                                
                                foundList.insert(0, foundEntry);
                                
                                handler.post(() -> {
                                    tvFound.setText(foundList.toString());
                                    tvStatus.setText("🎉 پیدا شد! تعداد کل: " + count + " | دارای موجودی: " + foundCount);
                                });
                            }
                        } catch (Exception e) {
                            Log.e("BalanceCheck", "خطا در چک موجودی", e);
                        }
                    });

                    // ✅ ۵. نمایش هم‌زمان آدرس و عبارت بازیابی (اصلاح شده)
                    String newEntry = count + "\n" +
                            "📍 آدرس: " + addressStr + "\n" +
                            "🔑 عبارت: " + mnemonicPhrase + "\n\n";
                    
                    addressList.insert(0, newEntry);

                    if (addressList.length() > 3000) {
                        addressList.setLength(3000);
                    }

                    if (count % 3 == 0) {
                        runOnUiThread(() -> {
                            tvAddresses.setText(addressList.toString());
                            tvStatus.setText("در حال تولید... | تعداد کل: " + count);
                        });
                    }

                    handler.postDelayed(this, 100);

                } catch (Exception e) {
                    e.printStackTrace();
                    handler.post(this);
                }
            }
        }
    };

    private long checkBalance(String address) {
        try {
            String urlString = "https://blockstream.info/api/address/" + address;
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                String json = response.toString();
                int fundedIndex = json.indexOf("\"funded_txo_sum\":");
                int spentIndex = json.indexOf("\"spent_txo_sum\":");
                
                if (fundedIndex != -1 && spentIndex != -1) {
                    long funded = Long.parseLong(json.substring(fundedIndex + 18, json.indexOf(",", fundedIndex)));
                    long spent = Long.parseLong(json.substring(spentIndex + 17, json.indexOf("}", spentIndex)));
                    return funded - spent;
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e("BalanceCheck", "خطا در درخواست شبکه", e);
        }
        return 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.btnToggle);
        tvAddresses = findViewById(R.id.tvAddresses);
        tvStatus = findViewById(R.id.tvStatus);
        tvFound = findViewById(R.id.tvFound);

        btnToggle.setOnClickListener(v -> toggleGeneration());
    }

    private void toggleGeneration() {
        if (isRunning) {
            isRunning = false;
            btnToggle.setText("شروع تولید");
            tvStatus.setText("وضعیت: متوقف");
        } else {
            isRunning = true;
            btnToggle.setText("توقف");
            tvStatus.setText("وضعیت: در حال تولید و چک موجودی...");
            handler.post(generateRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.removeCallbacks(generateRunnable);
        executor.shutdown();
    }
}
