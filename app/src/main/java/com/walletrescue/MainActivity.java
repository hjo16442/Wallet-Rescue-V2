package com.walletrescue;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.web3j.crypto.MnemonicUtils;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText inputText;
    private TextView outputText, statusText, speedText;
    private Button startBtn, stopBtn, generate12Btn, generate24Btn;
    private ScrollView outputScroll;
    private MnemonicRecovery engine;
    private long t0;
    private volatile boolean scanCancelled = false;
    private volatile boolean scanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText = findViewById(R.id.inputText);
        outputText = findViewById(R.id.outputText);
        statusText = findViewById(R.id.statusText);
        speedText = findViewById(R.id.speedText);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        generate12Btn = findViewById(R.id.generate12Btn);
        generate24Btn = findViewById(R.id.generate24Btn);
        outputScroll = findViewById(R.id.outputScroll);

        stopBtn.setEnabled(false);

        try {
            engine = new MnemonicRecovery(this);
        } catch (Exception e) {
            statusText.setText("خطا در بارگذاری فهرست BIP39: " + e.getMessage());
            startBtn.setEnabled(false);
            return;
        }

        startBtn.setOnClickListener(v -> onStartButtonPressed());
        stopBtn.setOnClickListener(v -> onStopClicked());
        generate12Btn.setOnClickListener(v -> generateMnemonic(16));
        generate24Btn.setOnClickListener(v -> generateMnemonic(32));
    }

    // Generate a wallet phrase locally using cryptographically secure entropy.
    private void generateMnemonic(int entropyBytes) {
        if (scanning) {
            statusText.setText("ابتدا بررسی جاری را متوقف کنید.");
            return;
        }
        byte[] entropy = new byte[entropyBytes];
        try {
            new SecureRandom().nextBytes(entropy);
            String phrase = MnemonicUtils.generateMnemonic(entropy);
            inputText.setText(phrase);
            inputText.setSelection(inputText.getText().length());
            outputText.setText("عبارت بازیابی جدید روی همین گوشی تولید شد. آن را روی کاغذ و در مکانی امن نگهداری کنید.\\n"
                    + "این عبارت را برای هیچ‌کس نفرستید و از آن عکس یا نسخه آنلاین نسازید.\\n"
                    + "بررسی آدرس‌های همین عبارت به‌صورت خودکار آغاز می‌شود؛ عبارت و کلید خصوصی ارسال نمی‌شوند.");
            statusText.setText("عبارت " + (entropyBytes == 16 ? "۱۲" : "۲۴")
                    + " کلمه‌ای BIP39 تولید شد — شروع بررسی خودکار...");
            speedText.setText("در حال آماده‌سازی بررسی شبکه اصلی...");
            beginWalletScan(phrase);
        } catch (Exception e) {
            statusText.setText("تولید عبارت ناموفق بود: " + e.getMessage());
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    // Start creates a fresh local wallet when the phrase field is empty.
    // If the owner has entered their own phrase, it checks that phrase instead.
    private void onStartButtonPressed() {
        String phrase = normalize(inputText.getText().toString());
        if (phrase.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("تولید عبارت بازیابی")
                    .setMessage("نوع عبارت جدید را انتخاب کنید. عبارت فقط روی همین گوشی تولید می‌شود.")
                    .setPositiveButton("۱۲ کلمه‌ای", (d, w) -> generateMnemonic(16))
                    .setNegativeButton("۲۴ کلمه‌ای", (d, w) -> generateMnemonic(32))
                    .setNeutralButton("انصراف", null)
                    .show();
        } else {
            onStartClicked();
        }
    }

    private void onStartClicked() {
        String phrase = normalize(inputText.getText().toString());
        if (phrase.isEmpty()) {
            statusText.setText("عبارت بازیابی کامل و متعلق به خودتان را وارد کنید.");
            return;
        }

        String[] words = phrase.split(" ");
        int missing = 0;
        for (String word : words) if ("?".equals(word)) missing++;

        if (missing == 0) {
            if (!SafeMnemonicRecovery.isValidCompletePhrase(this, phrase)) {
                statusText.setText("عبارت کامل نیست یا checksum استاندارد BIP39 را ندارد.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("تأیید بررسی کیف‌پول خودتان")
                    .setMessage("آدرس‌های مشتق‌شده از عبارت واردشده در شبکه‌های عمومی BTC و ETH بررسی می‌شوند. عبارت بازیابی و کلید خصوصی ارسال نمی‌شوند. ادامه می‌دهید؟")
                    .setPositiveButton("شروع بررسی", (d, w) -> beginWalletScan(phrase))
                    .setNegativeButton("لغو", null)
                    .show();
            return;
        }

        if (missing != 1) {
            statusText.setText("برای بازیابی، فقط یک کلمه را با ? مشخص کنید. جستجوی چند کلمه یا ۱۲ کلمه نامشخص عملی نیست.");
            return;
        }
        beginRecovery(phrase);
    }

    private void beginRecovery(String phrase) {
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        outputText.setText("");
        statusText.setText("در حال یافتن کلمه سازگار با checksum...");
        speedText.setText(" ");
        t0 = SystemClock.elapsedRealtime();

        engine.findAsync(phrase, false, new MnemonicRecovery.Callback() {
            @Override
            public void onProgress(long tested, long found) {
                long elapsed = SystemClock.elapsedRealtime() - t0;
                long speed = elapsed > 0 ? tested * 1000 / elapsed : 0;
                runOnUiThread(() -> {
                    statusText.setText("بررسی کلمات: " + fmt(tested) + " از ۲۰۴۸ | گزینه‌های معتبر: " + found);
                    speedText.setText("سرعت: " + fmt(speed) + " تست/ثانیه");
                });
            }

            @Override
            public void onResult(List<String> found) {
                runOnUiThread(() -> {
                    if (found.isEmpty()) {
                        outputText.setText("کلمه‌ای با checksum سازگار پیدا نشد.");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("عبارت‌های سازگار با BIP39 (هنوز موجودی بررسی نشده):\n\n");
                        for (int i = 0; i < found.size(); i++) {
                            sb.append(i + 1).append(". ").append(found.get(i)).append("\n\n");
                        }
                        sb.append("برای بررسی، فقط عبارت متعلق به خودتان را در کادر بالا قرار دهید و دوباره شروع را بزنید.");
                        outputText.setText(sb.toString());
                    }
                    statusText.setText("بازیابی پایان یافت — " + found.size() + " گزینه");
                    speedText.setText(" ");
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    statusText.setText("خطا در بازیابی");
                    outputText.setText(message);
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }
        });
    }

    private void beginWalletScan(String phrase) {
        scanCancelled = false;
        scanning = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        outputText.setText("بررسی آدرس‌های مشتق‌شده شروع شد...\n");
        statusText.setText("در حال بررسی BTC و ETH...");
        speedText.setText("۰ از ۴۰۰۰ آدرس");
        new Thread(() -> {
            try {
                String report = AutoWalletScanner.scan(
                        getApplicationContext(),
                        phrase,
                        1000,
                        (done, total) -> runOnUiThread(() -> {
                            if (!scanCancelled) {
                                statusText.setText("در حال بررسی شبکه‌ها...");
                                speedText.setText(fmt(done) + " از " + fmt(total) + " آدرس");
                            }
                        }),
                        () -> scanCancelled,
                        line -> runOnUiThread(() -> {
                            if (!scanCancelled) {
                                outputText.append("\n✓ نتیجه غیرصفر: " + line + "\n");
                                outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
                            }
                        })
                );
                runOnUiThread(() -> {
                    outputText.setText(report);
                    statusText.setText("بررسی تمام شد.");
                    speedText.setText(" ");
                    finishOperation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (scanCancelled) {
                        statusText.setText("بررسی متوقف شد.");
                    } else {
                        statusText.setText("خطا در بررسی شبکه");
                        outputText.append("\n" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                    speedText.setText(" ");
                    finishOperation();
                });
            }
        }).start();
    }

    private void onStopClicked() {
        scanCancelled = true;
        if (engine != null) engine.stop();
        if (!scanning) {
            statusText.setText("عملیات متوقف شد.");
            speedText.setText(" ");
            finishOperation();
        } else {
            statusText.setText("درخواست توقف بررسی شبکه ارسال شد...");
        }
    }

    private void finishOperation() {
        scanning = false;
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String fmt(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
