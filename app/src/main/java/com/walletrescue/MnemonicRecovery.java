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
