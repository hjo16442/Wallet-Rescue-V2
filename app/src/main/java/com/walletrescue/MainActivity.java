#!/bin/bash
set -e

ROOT="WalletRescue"
mkdir -p "$ROOT/app/src/main/java/com/walletrescue"
mkdir -p "$ROOT/app/src/main/res/layout"
mkdir -p "$ROOT/app/src/main/res/values"
mkdir -p "$ROOT/app/src/main/assets"

# ---------- settings.gradle ----------
cat > "$ROOT/settings.gradle" << 'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "WalletRescue"
include ':app'
EOF

# ---------- build.gradle (root) ----------
cat > "$ROOT/build.gradle" << 'EOF'
plugins {
    id 'com.android.application' version '8.2.2' apply false
}
EOF

# ---------- gradle.properties ----------
cat > "$ROOT/gradle.properties" << 'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
EOF

# ---------- app/build.gradle ----------
cat > "$ROOT/app/build.gradle" << 'EOF'
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.walletrescue'
    compileSdk 34

    defaultConfig {
        applicationId "com.walletrescue"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
}
EOF

# ---------- proguard ----------
cat > "$ROOT/app/proguard-rules.pro" << 'EOF'
# empty
EOF

# ---------- AndroidManifest ----------
cat > "$ROOT/app/src/main/AndroidManifest.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:icon="@android:drawable/ic_lock_lock"
        android:label="Wallet Rescue"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

# ---------- strings ----------
cat > "$ROOT/app/src/main/res/values/strings.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Wallet Rescue</string>
</resources>
EOF

# ---------- layout ----------
cat > "$ROOT/app/src/main/res/layout/activity_main.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="عبارت BIP39 (کلمات گمشده را با ? بنویسید):"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/inputText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:minLines="3"
            android:gravity="top|start"
            android:fontFamily="monospace"
            android:inputType="textMultiLine|textNoSuggestions"
            android:layout_marginTop="4dp" />

        <CheckBox
            android:id="@+id/forceBox"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="جستجوی کامل ۱۲/۲۴ (هشدار: هرگز تمام نمی‌شود)"
            android:textColor="#B00000"
            android:layout_marginTop="8dp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp">
            <Button
                android:id="@+id/startBtn"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="▶ شروع"
                android:layout_marginEnd="4dp" />
            <Button
                android:id="@+id/stopBtn"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="■ توقف"
                android:layout_marginStart="4dp" />
        </LinearLayout>

        <TextView
            android:id="@+id/statusText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="آماده"
            android:paddingTop="8dp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/speedText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text=" "
            android:textSize="12sp"
            android:textColor="#666" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="نتایج:"
            android:textStyle="bold"
            android:layout_marginTop="8dp" />

        <ScrollView
            android:id="@+id/outputScroll"
            android:layout_width="match_parent"
            android:layout_height="300dp"
            android:layout_marginTop="4dp"
            android:background="#F5F5F5"
            android:padding="8dp">
            <TextView
                android:id="@+id/outputText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:fontFamily="monospace"
                android:textIsSelectable="true"
                android:textSize="13sp" />
        </ScrollView>
    </LinearLayout>
</ScrollView>
EOF

# ---------- MnemonicRecovery.java ----------
cat > "$ROOT/app/src/main/java/com/walletrescue/MnemonicRecovery.java" << 'EOF'
package com.walletrescue;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class MnemonicRecovery {

    public interface Callback {
        void onProgress(long tested, long found);
        void onResult(List<String> found);
        void onError(String message);
    }

    private final List<String> dict;
    private final String[] dictArr;
    private final Map<String, Integer> wordIndex;
    private final AtomicLong tested = new AtomicLong(0);
    private volatile boolean cancelled = false;
    private ExecutorService pool;

    public MnemonicRecovery(Context context) throws Exception {
        List<String> list = new ArrayList<>(2048);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open("bip39.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) list.add(line);
            }
        }
        if (list.size() != 2048) {
            throw new Exception("bip39.txt باید ۲۰۴۸ کلمه داشته باشد (فعلی: " + list.size() + ")");
        }
        this.dict = Collections.unmodifiableList(list);
        this.dictArr = list.toArray(new String[0]);
        this.wordIndex = new HashMap<>(4096);
        for (int i = 0; i < dictArr.length; i++) wordIndex.put(dictArr[i], i);
    }

    public void stop() {
        cancelled = true;
        if (pool != null) pool.shutdownNow();
    }

    public long getTested() { return tested.get(); }

    public void findAsync(String phrase, boolean forceFull, Callback cb) {
        cancelled = false;
        tested.set(0);
        new Thread(() -> {
            try {
                List<String> r = find(phrase, forceFull, cb);
                cb.onResult(r);
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    private List<String> find(String phrase, boolean forceFull, Callback cb) throws Exception {
        String norm = (phrase == null) ? "" :
                Normalizer.normalize(phrase.trim(), Normalizer.Form.NFKD)
                        .toLowerCase(Locale.ROOT)
                        .replace('\u3000', ' ')
                        .replaceAll("\\s+", " ")
                        .trim();
        if (norm.isEmpty()) norm = String.join(" ", Collections.nCopies(12, "?"));

        String[] words = norm.split(" ");
        int n = words.length;
        if (n != 12 && n != 24) {
            throw new Exception("طول باید ۱۲ یا ۲۴ باشد (فعلی: " + n + ")");
        }

        int[] base = new int[n];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (words[i].equals("?")) {
                missing.add(i);
                base[i] = -1;
            } else {
                Integer idx = wordIndex.get(words[i]);
                if (idx == null) {
                    if (words[i].length() < 3) {
                        throw new Exception("پیشوند '" + words[i] + "' خیلی کوتاه است (جایگاه " + (i + 1) + ")");
                    }
                    List<String> pref = new ArrayList<>();
                    for (String w : dictArr) if (w.startsWith(words[i])) pref.add(w);
                    if (pref.isEmpty()) {
                        throw new Exception("پیشوند '" + words[i] + "' یافت نشد (جایگاه " + (i + 1) + ")");
                    }
                    if (pref.size() > 1) {
                        throw new Exception("پیشوند '" + words[i] + "' مبهم است (" + pref.size() + " گزینه)");
                    }
                    idx = wordIndex.get(pref.get(0));
                }
                base[i] = idx;
            }
        }

        if (missing.isEmpty()) {
            if (!isValidChecksum(base, n)) throw new Exception("checksum رد شد");
            return Collections.singletonList(String.join(" ", words));
        }

        if (missing.size() == 1 && missing.get(0) == n - 1) {
            int[] idx = base.clone();
            int last = computeLastIndex(idx, n);
            int[] withLast = idx.clone();
            withLast[n - 1] = last;
            if (last >= 0 && isValidChecksum(withLast, n)) {
                String[] solved = new String[n];
                for (int i = 0; i < n - 1; i++) solved[i] = dictArr[idx[i]];
                solved[n - 1] = dictArr[last];
                return Collections.singletonList(String.join(" ", solved));
            }
            return Collections.emptyList();
        }

        boolean fullSearch = missing.size() == n;
        if (fullSearch && !forceFull) {
            throw new Exception("همهٔ " + n + " کلمه گم شده‌اند (2^" +
                    (n == 12 ? 128 : 256) + " حالت). برای اجرای اجباری «جستجوی کامل» را فعال کنید.");
        }

        final List<String> results = Collections.synchronizedList(new ArrayList<>());
        int nThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
        pool = Executors.newFixedThreadPool(nThreads);
        int firstMissing = missing.get(0);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < nThreads; t++) {
                final int start = t * 2048 / nThreads;
                final int end = (t + 1) * 2048 / nThreads;
                futures.add(pool.submit(() -> {
                    int[] local = base.clone();
                    for (int v = start; v < end && !cancelled; v++) {
                        local[firstMissing] = v;
                        recurse(local, missing, 1, n, results, cb);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            if (pool != null) { pool.shutdownNow(); pool = null; }
        }
        return results;
    }

    private void recurse(int[] work, List<Integer> missing, int pos,
                         int n, List<String> results, Callback cb) {
        if (cancelled) return;
        if (pos == missing.size()) {
            long cnt = tested.incrementAndGet();
            if (isValidChecksum(work, n)) {
                StringBuilder sb = new StringBuilder(n * 10);
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(dictArr[work[i]]);
                }
                results.add(sb.toString());
            }
            if (cb != null && (cnt & 0x3FF) == 0) cb.onProgress(cnt, results.size());
            return;
        }
        int idx = missing.get(pos);
        for (int v = 0; v < 2048 && !cancelled; v++) {
            work[idx] = v;
            recurse(work, missing, pos + 1, n, results, cb);
        }
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    });

    private static boolean isValidChecksum(int[] indices, int n) {
        int csBits = n / 3;
        int entBits = n * 11 - csBits;
        byte[] entropy = new byte[entBits / 8];
        int bitPos = 0;
        for (int i = 0; i < n && bitPos < entBits; i++) {
            int idx = indices[i];
            for (int b = 10; b >= 0 && bitPos < entBits; b--) {
                if (((idx >> b) & 1) == 1)
                    entropy[bitPos >> 3] |= (byte) (1 << (7 - (bitPos & 7)));
                bitPos++;
            }
        }
        byte[] hash = SHA256.get().digest(entropy);
        int hBit = 0;
        for (int i = 0; i < n; i++) {
            int idx = indices[i];
            for (int b = 10; b >= 0; b--) {
                int globalBit = i * 11 + (10 - b);
                if (globalBit < entBits) continue;
                int expected = (hash[hBit >> 3] >> (7 - (hBit & 7))) & 1;
                int actual = (idx >> b) & 1;
                if (expected != actual) return false;
                hBit++;
            }
        }
        return true;
    }

    private static int computeLastIndex(int[] indices, int n) {
        int csBits = n / 3;
        int entBits = n * 11 - csBits;
        byte[] entropy = new byte[entBits / 8];
        int bitPos = 0;
        for (int i = 0; i < n - 1; i++) {
            int idx = indices[i];
            for (int b = 10; b >= 0 && bitPos < entBits; b--) {
                if (((idx >> b) & 1) == 1)
                    entropy[bitPos >> 3] |= (byte) (1 << (7 - (bitPos & 7)));
                bitPos++;
            }
        }
        byte[] hash = SHA256.get().digest(entropy);
        int cs = 0;
        for (int i = 0; i < csBits; i++) cs = (cs << 1) | ((hash[i >> 3] >> (7 - (i & 7))) & 1);
        return cs;
    }
}
EOF

# ---------- MainActivity.java ----------
cat > "$ROOT/app/src/main/java/com/walletrescue/MainActivity.java" << 'EOF'
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
EOF

# ---------- دانلود bip39.txt ----------
echo "⬇️  دانلود bip39.txt ..."
if command -v curl &> /dev/null; then
    curl -sSL -o "$ROOT/app/src/main/assets/bip39.txt" \
        https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt
elif command -v wget &> /dev/null; then
    wget -q -O "$ROOT/app/src/main/assets/bip39.txt" \
        https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt
else
    echo "⚠️  curl یا wget نصب نیست. لطفاً bip39.txt را دستی دانلود کنید."
fi

if [ -f "$ROOT/app/src/main/assets/bip39.txt" ]; then
    LINES=$(wc -l < "$ROOT/app/src/main/assets/bip39.txt")
    echo "✅ bip39.txt دانلود شد ($LINES خط)"
fi

echo ""
echo "✅ پروژه ساخته شد: $ROOT/"
echo "👉 در Android Studio باز کنید: File → Open → $ROOT"
echo "👉 سپس Sync و Run."
