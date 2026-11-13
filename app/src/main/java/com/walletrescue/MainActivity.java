package com.example.bitcoingen;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.params.MainNetParams;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

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
                    // ۱. تولید ۱۶ بایت آنتروپی تصادفی امن (معادل ۱۲ کلمه)
                    byte[] entropy = new byte[16];
                    new SecureRandom().nextBytes(entropy);

                    // ۲. تبدیل آنتروپی به لیست کلمات (عبارت بازیابی)
                    List<String> mnemonicList = MnemonicCode.INSTANCE.toMnemonic(entropy);
                    String mnemonicPhrase = String.join(" ", mnemonicList);

                    // ۳. تبدیل عبارت به Seed (۶۴ بایت) و ساخت کلید اصلی (Master Key) - ✅ اصلاح شده
                    byte[] seedBytes = MnemonicCode.INSTANCE.toSeed(mnemonicList, "");
                    DeterministicKey masterKey = HDKeyDerivation.createMasterPrivateKey(seedBytes);

                    // ۴. مسیر مشتق‌سازی استاندارد BIP84 (برای آدرس‌های Native SegWit)
                    List<ChildNumber> path = Arrays.asList(
                            new ChildNumber(84, true),   // 84'
                            new ChildNumber(0, true),    // 0' (بیت‌کوین)
                            new ChildNumber(0, true),    // 0' (حساب ۰)
                            new ChildNumber(0, false),   // 0  (زنجیره دریافت)
                            new ChildNumber(0, false)    // 0  (ایندکس آدرس)
                    );

                    // ۵. استخراج کلید فرزند از روی مسیر (باید تک‌تک پیمایش شود) - ✅ اصلاح شده
                    DeterministicKey childKey = masterKey;
                    for (ChildNumber childNumber : path) {
                        childKey = HDKeyDerivation.deriveChildKey(childKey, childNumber);
                    }

                    // ۶. تولید آدرس بیت‌کوین از کلید فرزند
                    SegwitAddress address = SegwitAddress.fromKey(MainNetParams.get(), childKey);

                    count++;
                    
                    // نمایش هم‌زمان آدرس و عبارت بازیابی
                    String newEntry = count + "- آدرس: " + address.toBech32() + "\nعبارت: " + mnemonicPhrase + "\n\n";
                    addressList.insert(0, newEntry);

                    // ✅ بهینه‌سازی: جلوگیری از پر شدن حافظه و کرش کردن برنامه در اجرای طولانی‌مدت
                    // فقط ۲۰ مورد آخر را نگه می‌دارد
                    String[] lines = addressList.toString().split("\n\n");
                    if (lines.length > 20) {
                        addressList.setLength(0);
                        for (int i = 0; i < 20; i++) {
                            addressList.append(lines[i]).append("\n\n");
                        }
                    }

                    // به‌روزرسانی رابط کاربری هر ۳ بار
                    if (count % 3 == 0) {
                        runOnUiThread(() -> {
                            tvAddresses.setText(addressList.toString());
                            tvStatus.setText("در حال تولید... | تعداد کل: " + count);
                        });
                    }

                    // ادامه حلقه به صورت خودکار
                    handler.post(this);

                } catch (Exception e) {
                    e.printStackTrace();
                    // در صورت خطا، برای جلوگیری از توقف کامل، دوباره تلاش می‌کند
                    handler.post(this);
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
            tvStatus.setText("وضعیت: متوقف");
        } else {
            isRunning = true;
            btnToggle.setText("توقف");
            tvStatus.setText("وضعیت: در حال تولید");
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
