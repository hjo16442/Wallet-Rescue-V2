package com.walletrescue;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.MnemonicUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText inputText;
    private TextView outputText, statusText, speedText;
    private Button startBtn, stopBtn, generate12Btn, generate24Btn, publicAddressBtn;
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
        publicAddressBtn = findViewById(R.id.publicAddressBtn);
        publicAddressBtn.setOnClickListener(v -> showPublicAddressScanner());

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

    // ---------- تولید عبارت جدید ----------
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
            outputText.setText("عبارت بازیابی جدید روی همین گوشی تولید شد. آن را روی کاغذ و در مکانی امن نگهداری کنید.\n"
                    + "این عبارت را برای هیچ‌کس نفرستید و از آن عکس یا نسخه آنلاین نسازید.\n"
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

    // ---------- شروع بررسی با عبارت ----------
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
                    .setMessage("آدرس‌های مشتق‌شده از عبارت واردشده در شبکه اصلی بیت‌کوین بررسی می‌شوند. عبارت بازیابی و کلید خصوصی ارسال نمی‌شوند. ادامه می‌دهید؟")
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

    // ==========================================================
    //  بخش آدرس: انتخاب حالت (بررسی موجودی / تطبیق با عبارت‌ها)
    // ==========================================================
    private void showPublicAddressScanner() {
        if (scanning) {
            statusText.setText("ابتدا بررسی جاری را متوقف کنید.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ابزار بررسی آدرس")
                .setItems(new String[]{
                        "بررسی موجودی فهرست آدرس‌های عمومی",
                        "تطبیق آدرس هدف با عبارت‌های بازیابی"
                }, (d, which) -> {
                    if (which == 0) {
                        showBalanceScannerDialog();
                    } else {
                        showMatchWithPhrasesDialog();
                    }
                })
                .show();
    }

    // ---------- حالت ۱: بررسی موجودی فهرست آدرس‌ها (رفتار قبلی) ----------
    private void showBalanceScannerDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, 0);

        EditText addressesInput = new EditText(this);
        addressesInput.setHint("هر آدرس را در یک خط وارد کنید (حداکثر ۱۰۰ آدرس)");
        addressesInput.setMinLines(5);
        addressesInput.setMaxLines(8);
        addressesInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        addressesInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        panel.addView(addressesInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("بررسی فهرست آدرس‌های عمومی")
                .setMessage("فقط آدرس‌هایی را وارد کنید که مالک آن‌ها هستید یا اجازه بررسی‌شان را دارید.")
                .setView(panel)
                .setPositiveButton("شروع بررسی", (dialog, which) -> {
                    String raw = addressesInput.getText().toString();
                    List<String> list = new ArrayList<>();
                    for (String line : raw.split("[\\r\\n,;\\s]+")) {
                        if (!line.trim().isEmpty()) list.add(line.trim());
                    }
                    if (list.isEmpty()) {
                        statusText.setText("ابتدا آدرس‌های عمومی را وارد کنید.");
                        return;
                    }
                    beginPublicAddressScan(list, 0);
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void beginPublicAddressScan(List<String> addresses, int network) {
        scanCancelled = false;
        scanning = true;
        startBtn.setEnabled(false);
        publicAddressBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        outputText.setText("بررسی فهرست آدرس‌های عمومی آغاز شد...\n");
        statusText.setText("در حال بررسی " + (network == 0 ? "BTC" : "ETH") + "...");
        speedText.setText("۰ از " + fmt(addresses.size()) + " آدرس");
        t0 = SystemClock.elapsedRealtime();

        new Thread(() -> {
            try {
                String report = AddressScanner.scan(this, addresses, network,
                        (done, total) -> runOnUiThread(() -> {
                            if (!scanCancelled) {
                                long elapsed = Math.max(1, SystemClock.elapsedRealtime() - t0);
                                long rate = done * 1000L / elapsed;
                                statusText.setText("بررسی موازی — " + done + " از " + total);
                                speedText.setText("⚡ " + fmt(done) + " / " + fmt(total)
                                        + " آدرس  |  " + fmt(rate) + " آدرس/ثانیه");
                            }
                        }),
                        () -> scanCancelled);
                runOnUiThread(() -> {
                    outputText.setText(report);
                    statusText.setText(scanCancelled ? "بررسی متوقف شد." : "بررسی فهرست تمام شد.");
                    speedText.setText(" ");
                    finishPublicAddressOperation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText(scanCancelled ? "بررسی متوقف شد." : "خطا در بررسی آدرس‌ها");
                    outputText.append("\n" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    speedText.setText(" ");
                    finishPublicAddressOperation();
                });
            }
        }).start();
    }

    // ---------- حالت ۲: تطبیق آدرس هدف با عبارت‌ها ----------
    private void showMatchWithPhrasesDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, 0);

        EditText targetInput = new EditText(this);
        targetInput.setHint("آدرس عمومی کیف پول هدف (مثلاً 0x...)");
        targetInput.setSingleLine(true);
        targetInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        panel.addView(targetInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText phrasesInput = new EditText(this);
        phrasesInput.setHint("هر عبارت بازیابی را در یک خط جداگانه وارد کنید (یکی در هر خط)");
        phrasesInput.setMinLines(5);
        phrasesInput.setMaxLines(10);
        phrasesInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        phrasesInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        panel.addView(phrasesInput, lp);

        new AlertDialog.Builder(this)
                .setTitle("تطبیق آدرس هدف با عبارت‌ها")
                .setMessage("آدرس هدف و عبارت‌های بازیابی متعلق به خودتان را وارد کنید. برنامه آدرس‌ها را از هر عبارت مشتق و با آدرس هدف مقایسه می‌کند. هیچ چیزی از گوشی خارج نمی‌شود.")
                .setView(panel)
                .setPositiveButton("شروع تطبیق", (dialog, which) -> {
                    String target = targetInput.getText().toString().trim();
                    if (target.isEmpty()) {
                        statusText.setText("آدرس هدف را وارد کنید.");
                        return;
                    }
                    List<String> phrases = new ArrayList<>();
                    for (String line : phrasesInput.getText().toString().split("\\r?\\n")) {
                        String t = line.trim();
                        if (!t.isEmpty()) phrases.add(t);
                    }
                    if (phrases.isEmpty()) {
                        statusText.setText("حداقل یک عبارت بازیابی وارد کنید.");
                        return;
                    }
                    beginAddressMatching(target, phrases);
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void beginAddressMatching(String target, List<String> phrases) {
        scanCancelled = false;
        scanning = true;
        startBtn.setEnabled(false);
        publicAddressBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        String targetLower = target.toLowerCase(Locale.ROOT);
        boolean isEth = targetLower.startsWith("0x") && targetLower.length() == 42;
        if (!isEth) {
            scanning = false;
            startBtn.setEnabled(true);
            publicAddressBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            statusText.setText("آدرس نامعتبر یا پشتیبانی‌نشده");
            outputText.setText("آدرس هدف باید یک آدرس ETH معتبر (0x + 40 کاراکتر hex) باشد.\n"
                    + "برای BTC تطبیق در این نسخه پشتیبانی نمی‌شود.");
            return;
        }

        outputText.setText("آدرس هدف: " + target + "\n\nدر حال بررسی " + phrases.size() + " عبارت...\n\n");
        statusText.setText("آماده‌سازی تطبیق...");
        speedText.setText("۰ از " + fmt(phrases.size()));

        new Thread(() -> {
            try {
                StringBuilder report = new StringBuilder();
                report.append("آدرس هدف: ").append(target).append("\n\n");
                int matches = 0;

                for (int i = 0; i < phrases.size(); i++) {
                    if (scanCancelled) break;
                    final int idx = i;

                    runOnUiThread(() -> {
                        statusText.setText("بررسی عبارت " + (idx + 1) + " از " + phrases.size());
                        speedText.setText(fmt(idx + 1) + " از " + fmt(phrases.size()));
                    });

                    String phrase = normalize(phrases.get(i));
                    String matchInfo = findEthMatch(phrase, targetLower);

                    if (matchInfo != null) {
                        matches++;
                        report.append("عبارت ").append(i + 1)
                              .append(" → ✅ تطبیق: ").append(matchInfo).append("\n");
                    } else {
                        report.append("عبارت ").append(i + 1)
                              .append(" → ❌ بدون تطبیق\n");
                    }

                    final String partial = report.toString();
                    runOnUiThread(() -> {
                        outputText.setText(partial);
                        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
                    });
                }

                final int finalMatches = matches;
                final String finalReport = report.toString();
                runOnUiThread(() -> {
                    outputText.setText(finalReport
                            + "\nنتیجه: " + (scanCancelled ? "متوقف شد" : "پایان یافت")
                            + " — تعداد تطبیق: " + finalMatches);
                    statusText.setText(scanCancelled ? "تطبیق متوقف شد." : "تطبیق تمام شد.");
                    speedText.setText(" ");
                    finishPublicAddressOperation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    outputText.append("\nخطا: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    statusText.setText(scanCancelled ? "تطبیق متوقف شد." : "خطا در تطبیق");
                    speedText.setText(" ");
                    finishPublicAddressOperation();
                });
            }
        }).start();
    }

    // اشتقاق آدرس‌های ETH با مسیر BIP44 و مقایسه با آدرس هدف
    private String findEthMatch(String mnemonic, String targetLower) {
        try {
            byte[] seed = MnemonicUtils.generateSeed(mnemonic, "");
            Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);
            for (int i = 0; i < 20; i++) {
                int[] path = new int[]{
                        44 | Bip32ECKeyPair.HARDENED_BIT,
                        60 | Bip32ECKeyPair.HARDENED_BIT,
                        0  | Bip32ECKeyPair.HARDENED_BIT,
                        0,
                        i
                };
                Bip32ECKeyPair child = Bip32ECKeyPair.deriveKeyPair(master, path);
                String addr = "0x" + Keys.getAddress(child);
                if (addr.toLowerCase(Locale.ROOT).equals(targetLower)) {
                    return "m/44'/60'/0'/0/" + i + " → " + addr;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void finishPublicAddressOperation() {
        scanning = false;
        startBtn.setEnabled(true);
        publicAddressBtn.setEnabled(true);
        stopBtn.setEnabled(false);
    }

    private void beginWalletScan(String phrase) {
        scanCancelled = false;
        scanning = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        outputText.setText("بررسی آدرس‌های مشتق‌شده شروع شد...\n");
        statusText.setText("در حال بررسی بیت‌کوین...");
        speedText.setText("۰ از ۲۰۰۰ آدرس");
        new Thread(() -> {
            try {
                String report = AutoWalletScanner.scan(
                        getApplicationContext(),
                        phrase,
                        1000,
                        (done, total) -> runOnUiThread(() -> {
                            if (!scanCancelled) {
                                statusText.setText("در حال بررسی بیت‌کوین...");
                                speedText.setText(fmt(done) + " از " + fmt(total) + " آدرس");
                            }
                        }),
                        () -> scanCancelled,
                        line -> runOnUiThread(() -> {
                            if (!scanCancelled) {
                                outputText.setText("⭐ موجودی مثبت بیت‌کوین پیدا شد:\n"
                                        + line + "\n\n" + outputText.getText());
                                outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_UP));
                                Toast.makeText(getApplicationContext(),
                                        "موجودی مثبت در یک آدرس بیت‌کوین پیدا شد",
                                        Toast.LENGTH_LONG).show();
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
