package com.walletrescue;

import org.bitcoinj.crypto.MnemonicCode;

import javax.swing.*;
import java.awt.*;
import java.text.Normalizer;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * برنامهٔ بازیابی عبارت BIP39 (۱۲ یا ۲۴ کلمه)
 *
 * قابلیت‌ها:
 *  - جستجوی جزئی (۱ تا ۳ کلمه گمشده) با حداکثر سرعت
 *  - محاسبهٔ مستقیم کلمهٔ آخر (بدون جستجو)
 *  - جستجوی کامل ۱۲/۲۴ کلمه (با force — عملاً هرگز تمام نمی‌شود)
 *  - چندرشته‌ای (تمام هسته‌های CPU)
 *  - SHA-256 دستی بهینه (بدون MessageDigest)
 *  - دکمه توقف + نمایش پیشرفت زنده
 *
 * استفاده:
 *   javac -cp bitcoinj-core.jar MnemonicRecoveryApp.java
 *   java  -cp .:bitcoinj-core.jar com.walletrescue.MnemonicRecoveryApp
 *
 * یا اگر UI نمی‌خواهید، متد main را با نسخهٔ CLI جایگزین کنید (پایین کامنت شده).
 */
public class MnemonicRecoveryApp {

    // =====================================================================
    //  بخش ۱: موتور بازیابی (UltraFastRecovery)
    // =====================================================================

    public static class UltraFastRecovery {

        private static final int[] VALID_LENGTHS = {12, 24};
        private static final List<String> DICT = MnemonicCode.INSTANCE.getWordList();
        private static final String[] DICT_ARR = DICT.toArray(new String[0]);
        private static final Map<String, Integer> WORD_INDEX = new HashMap<>(4096);

        private static final ThreadLocal<FastSha256> SHA =
                ThreadLocal.withInitial(FastSha256::new);

        static {
            for (int i = 0; i < DICT_ARR.length; i++) WORD_INDEX.put(DICT_ARR[i], i);
        }

        private final AtomicLong tested = new AtomicLong(0);
        private volatile boolean cancelled = false;

        public void stop()        { cancelled = true; }
        public void reset()       { cancelled = false; tested.set(0); }
        public long getTested()   { return tested.get(); }

        public List<String> find(String phrase, boolean forceFullSearch) throws Exception {
            return find(phrase, forceFullSearch, null);
        }

        public List<String> find(String phrase, boolean forceFullSearch,
                                 ProgressCallback cb) throws Exception {
            reset();

            // ---------- نرمال‌سازی ----------
            String norm = (phrase == null) ? "" :
                    Normalizer.normalize(phrase.trim(), Normalizer.Form.NFKD)
                            .toLowerCase(Locale.ROOT)
                            .replace('\u3000', ' ')
                            .replaceAll("\\s+", " ")
                            .trim();
            if (norm.isEmpty()) {
                norm = String.join(" ", Collections.nCopies(12, "?"));
            }

            String[] words = norm.split(" ");
            int n = words.length;
            boolean lenOk = false;
            for (int len : VALID_LENGTHS) if (n == len) { lenOk = true; break; }
            if (!lenOk) throw new Exception("طول باید ۱۲ یا ۲۴ باشد (فعلی: " + n + ")");

            // ---------- تحلیل کلمات ----------
            int[] base = new int[n];
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (words[i].equals("?")) {
                    missing.add(i);
                    base[i] = -1;
                } else {
                    Integer idx = WORD_INDEX.get(words[i]);
                    if (idx == null) {
                        if (words[i].length() < 3)
                            throw new Exception("پیشوند '" + words[i] +
                                    "' خیلی کوتاه است (جایگاه " + (i+1) + ")");
                        List<String> pref = new ArrayList<>();
                        for (String w : DICT_ARR) if (w.startsWith(words[i])) pref.add(w);
                        if (pref.isEmpty())
                            throw new Exception("پیشوند '" + words[i] +
                                    "' یافت نشد (جایگاه " + (i+1) + ")");
                        if (pref.size() > 1)
                            throw new Exception("پیشوند '" + words[i] +
                                    "' مبهم است (" + pref.size() + " گزینه) — از BTCRecover استفاده کنید");
                        idx = WORD_INDEX.get(pref.get(0));
                    }
                    base[i] = idx;
                }
            }

            // ---------- حالت ۱: بدون ? ----------
            if (missing.isEmpty()) {
                if (!isValidChecksum(base, n))
                    throw new Exception("checksum رد شد — عبارت معتبر نیست");
                return new ArrayList<>(Collections.singletonList(String.join(" ", words)));
            }

            // ---------- حالت ۲: فقط آخرین ? → محاسبهٔ مستقیم ----------
            if (missing.size() == 1 && missing.get(0) == n - 1) {
                int[] idx = base.clone();
                int last = computeLastIndex(idx, n);
                int[] withLast = idx.clone();
                withLast[n - 1] = last;
                if (last >= 0 && isValidChecksum(withLast, n)) {
                    String[] solved = new String[n];
                    for (int i = 0; i < n - 1; i++) solved[i] = DICT_ARR[idx[i]];
                    solved[n - 1] = DICT_ARR[last];
                    return new ArrayList<>(Collections.singletonList(String.join(" ", solved)));
                }
                return Collections.emptyList();
            }

            // ---------- محافظت ----------
            long total = 1L;
            for (int i = 0; i < missing.size(); i++) {
                total *= 2048L;
                if (total < 0) total = Long.MAX_VALUE;
            }

            boolean fullSearch = missing.size() == n;
            if (fullSearch && !forceFullSearch) {
                throw new Exception(
                        "همهٔ " + n + " کلمه گم شده‌اند (2^" + (n == 12 ? 128 : 256) +
                        " حالت). برای اجرای اجباری، گزینهٔ «جستجوی کامل» را تیک بزنید. " +
                        "هشدار: هرگز به پایان نمی‌رسد.");
            }

            // ---------- جستجوی چندرشته‌ای ----------
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            int nThreads = Runtime.getRuntime().availableProcessors();
            ExecutorService pool = Executors.newFixedThreadPool(nThreads);
            int firstMissing = missing.get(0);

            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < nThreads; t++) {
                    final int start = t * 2048 / nThreads;
                    final int end = (t + 1) * 2048 / nThreads;
                    futures.add(pool.submit(() -> {
                        int[] local = base.clone();
                        IntStream.range(start, end).forEach(v -> {
                            if (cancelled) return;
                            local[firstMissing] = v;
                            recurse(local, missing, 1, n, results, cb);
                        });
                    }));
                }
                for (Future<?> f : futures) f.get();
            } finally {
                pool.shutdownNow();
            }

            return results;
        }

        private void recurse(int[] work, List<Integer> missing, int pos,
                             int n, List<String> results, ProgressCallback cb) {
            if (cancelled) return;
            if (pos == missing.size()) {
                long cnt = tested.incrementAndGet();
                if (isValidChecksum(work, n)) {
                    StringBuilder sb = new StringBuilder(n * 10);
                    for (int i = 0; i < n; i++) {
                        if (i > 0) sb.append(' ');
                        sb.append(DICT_ARR[work[i]]);
                    }
                    results.add(sb.toString());
                }
                if (cb != null && (cnt & 0x3FFFF) == 0) cb.onProgress(cnt, results.size());
                return;
            }
            int idx = missing.get(pos);
            for (int v = 0; v < 2048; v++) {
                if (cancelled) return;
                work[idx] = v;
                recurse(work, missing, pos + 1, n, results, cb);
            }
        }

        private static boolean isValidChecksum(int[] indices, int n) {
            int csBits = n / 3;
            int entBits = n * 11 - csBits;
            int entBytes = entBits / 8;
            byte[] entropy = new byte[entBytes];
            int bitPos = 0;
            for (int i = 0; i < n && bitPos < entBits; i++) {
                int idx = indices[i];
                for (int b = 10; b >= 0 && bitPos < entBits; b--) {
                    if (((idx >> b) & 1) == 1)
                        entropy[bitPos >> 3] |= (byte)(1 << (7 - (bitPos & 7)));
                    bitPos++;
                }
            }
            FastSha256 sha = SHA.get();
            sha.hash(entropy);
            byte[] hash = sha.getDigest();
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
            int entBytes = entBits / 8;
            byte[] entropy = new byte[entBytes];
            int bitPos = 0;
            for (int i = 0; i < n - 1; i++) {
                int idx = indices[i];
                for (int b = 10; b >= 0 && bitPos < entBits; b--) {
                    if (((idx >> b) & 1) == 1)
                        entropy[bitPos >> 3] |= (byte)(1 << (7 - (bitPos & 7)));
                    bitPos++;
                }
            }
            FastSha256 sha = SHA.get();
            sha.hash(entropy);
            byte[] hash = sha.getDigest();
            int cs = 0;
            for (int i = 0; i < csBits; i++)
                cs = (cs << 1) | ((hash[i >> 3] >> (7 - (i & 7))) & 1);
            return cs;
        }

        @FunctionalInterface
        public interface ProgressCallback {
            void onProgress(long tested, long found);
        }
    }

    // =====================================================================
    //  بخش ۲: SHA-256 دستی و بهینه
    // =====================================================================

    public static final class FastSha256 {
        private static final int[] K = {
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,
            0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,
            0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,
            0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,
            0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,
            0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,
            0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,
            0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
            0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
        };

        private final int[] H = new int[8];
        private final int[] W = new int[64];
        private final byte[] digest = new byte[32];

        void hash(byte[] data) {
            H[0]=0x6a09e667; H[1]=0xbb67ae85; H[2]=0x3c6ef372; H[3]=0xa54ff53a;
            H[4]=0x510e527f; H[5]=0x9b05688c; H[6]=0x1f83d9ab; H[7]=0x5be0cd19;

            int len = data.length;
            long bitLen = (long) len * 8;
            int totalLen = ((len + 9 + 63) / 64) * 64;
            byte[] buf = new byte[totalLen];
            System.arraycopy(data, 0, buf, 0, len);
            buf[len] = (byte) 0x80;
            for (int i = 0; i < 8; i++)
                buf[totalLen - 1 - i] = (byte)(bitLen >>> (8 * i));

            for (int off = 0; off < totalLen; off += 64) {
                for (int i = 0; i < 16; i++) {
                    W[i] = ((buf[off+i*4] & 0xFF) << 24) |
                           ((buf[off+i*4+1] & 0xFF) << 16) |
                           ((buf[off+i*4+2] & 0xFF) << 8) |
                           (buf[off+i*4+3] & 0xFF);
                }
                for (int i = 16; i < 64; i++) {
                    int s0 = Integer.rotateRight(W[i-15],7) ^
                             Integer.rotateRight(W[i-15],18) ^ (W[i-15] >>> 3);
                    int s1 = Integer.rotateRight(W[i-2],17) ^
                             Integer.rotateRight(W[i-2],19) ^ (W[i-2] >>> 10);
                    W[i] = W[i-16] + s0 + W[i-7] + s1;
                }

                int a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
                for (int i = 0; i < 64; i++) {
                    int S1 = Integer.rotateRight(e,6) ^
                             Integer.rotateRight(e,11) ^
                             Integer.rotateRight(e,25);
                    int ch = (e & f) ^ (~e & g);
                    int t1 = h + S1 + ch + K[i] + W[i];
                    int S0 = Integer.rotateRight(a,2) ^
                             Integer.rotateRight(a,13) ^
                             Integer.rotateRight(a,22);
                    int maj = (a & b) ^ (a & c) ^ (b & c);
                    int t2 = S0 + maj;
                    h=g; g=f; f=e; e=d+t1;
                    d=c; c=b; b=a; a=t1+t2;
                }
                H[0]+=a; H[1]+=b; H[2]+=c; H[3]+=d;
                H[4]+=e; H[5]+=f; H[6]+=g; H[7]+=h;
            }

            for (int i = 0; i < 8; i++) {
                digest[i*4]   = (byte)(H[i] >>> 24);
                digest[i*4+1] = (byte)(H[i] >>> 16);
                digest[i*4+2] = (byte)(H[i] >>> 8);
                digest[i*4+3] = (byte)(H[i]);
            }
        }

        byte[] getDigest() { return digest; }
    }

    // =====================================================================
    //  بخش ۳: رابط گرافیکی (Swing)
    // =====================================================================

    private final UltraFastRecovery engine = new UltraFastRecovery();
    private Thread worker;

    private final JTextArea inputArea    = new JTextArea(3, 60);
    private final JTextArea outputArea   = new JTextArea(10, 60);
    private final JCheckBox forceBox     = new JCheckBox("جستجوی کامل (کل ۱۲/۲۴ کلمه) — هشدار: هرگز تمام نمی‌شود");
    private final JButton startBtn       = new JButton("▶ شروع");
    private final JButton stopBtn        = new JButton("■ توقف");
    private final JLabel  statusLabel    = new JLabel("آماده");
    private final JLabel  speedLabel     = new JLabel(" ");

    public MnemonicRecoveryApp() {
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setText("? ? ? ? ? ? ? ? ? ? ? ?");
        inputArea.setFont(new Font("Monospaced", Font.PLAIN, 13));

        outputArea.setEditable(false);
        outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        forceBox.setForeground(new Color(180, 0, 0));
        stopBtn.setEnabled(false);

        startBtn.addActionListener(e -> onStart());
        stopBtn.addActionListener(e -> onStop());
    }

    private void onStart() {
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        outputArea.setText("");
        statusLabel.setText("در حال شروع...");
        speedLabel.setText(" ");

        String phrase = inputArea.getText();
        boolean force = forceBox.isSelected();

        // هشدار برای جستجوی کامل
        int missingCount = 0;
        int wordCount = phrase.trim().isEmpty() ? 12 : phrase.trim().split("\\s+").length;
        for (String w : phrase.trim().split("\\s+")) if (w.equals("?")) missingCount++;
        if (phrase.trim().isEmpty()) missingCount = 12;

        if (missingCount == wordCount && wordCount > 0) {
            int c = JOptionPane.showConfirmDialog(null,
                    "همهٔ " + wordCount + " کلمه گم شده‌اند.\n" +
                    "جستجوی کامل از نظر ریاضی غیرممکن است (2^" +
                    (wordCount == 12 ? 128 : 256) + " حالت).\n" +
                    "این عملیات هرگز به پایان نمی‌رسد.\n\n" +
                    "آیا مطمئن هستید؟",
                    "هشدار جدی", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c != JOptionPane.YES_OPTION) {
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                statusLabel.setText("لغو شد");
                return;
            }
            force = true;
        }

        final boolean forceFinal = force;
        worker = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<String> found = engine.find(phrase, forceFinal,
                        (tested, foundCnt) -> {
                            long elapsed = System.currentTimeMillis() - t0;
                            long speed = elapsed > 0 ? tested * 1000 / elapsed : 0;
                            SwingUtilities.invokeLater(() -> {
                                statusLabel.setText(String.format(
                                        "تست: %,d | یافته: %d", tested, foundCnt));
                                speedLabel.setText(String.format(
                                        "سرعت: %,d تست/ثانیه", speed));
                            });
                        });

                long elapsed = System.currentTimeMillis() - t0;
                SwingUtilities.invokeLater(() -> {
                    if (found.isEmpty()) {
                        statusLabel.setText("تمام شد — چیزی یافت نشد");
                        outputArea.setText("هیچ عبارت معتبری یافت نشد.\n" +
                                "زمان: " + (elapsed / 1000.0) + " ثانیه\n" +
                                "تست‌شده: " + engine.getTested());
                    } else {
                        statusLabel.setText("تمام شد — " + found.size() + " نتیجه");
                        StringBuilder sb = new StringBuilder();
                        for (String s : found) sb.append(s).append("\n");
                        outputArea.setText(sb.toString());
                        outputArea.setCaretPosition(0);
                    }
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("خطا");
                    outputArea.setText("خطا: " + ex.getMessage());
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void onStop() {
        engine.stop();
        statusLabel.setText("متوقف شد — تست‌شده: " + engine.getTested());
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
    }

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // بالای صفحه: ورودی
        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("عبارت (کلمات معلوم + ? برای گمشده):"), BorderLayout.NORTH);
        top.add(new JScrollPane(inputArea), BorderLayout.CENTER);

        JPanel topSouth = new JPanel(new BorderLayout());
        topSouth.add(forceBox, BorderLayout.WEST);
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnPanel.add(startBtn);
        btnPanel.add(stopBtn);
        topSouth.add(btnPanel, BorderLayout.EAST);
        top.add(topSouth, BorderLayout.SOUTH);

        // پایین: خروجی
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(new JLabel("نتایج:"), BorderLayout.NORTH);
        bottom.add(new JScrollPane(outputArea), BorderLayout.CENTER);

        // نوار وضعیت
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(speedLabel, BorderLayout.EAST);
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        root.add(top, BorderLayout.NORTH);
        root.add(bottom, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        return root;
    }

    // =====================================================================
    //  بخش ۴: main
    // =====================================================================

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("بازیابی عبارت BIP39 — ۱۲/۲۴ کلمه");
            MnemonicRecoveryApp app = new MnemonicRecoveryApp();
            f.setContentPane(app.buildUI());
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(820, 640);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }

    // =====================================================================
    //  گزینه: نسخهٔ CLI (بدون UI)
    //  اگر می‌خواهید بدون پنجره اجرا کنید، این متد را باز کنید
    //  و main بالا را کامنت کنید.
    // =====================================================================
    /*
    public static void cliMain(String[] args) throws Exception {
        UltraFastRecovery r = new UltraFastRecovery();
        String phrase = args.length > 0 ? args[0] : "? ? ? ? ? ? ? ? ? ? ? ?";
        boolean force = args.length > 1 && args[1].equals("--force");
        System.out.println("شروع...");
        List<String> found = r.find(phrase, force,
                (t, f) -> System.out.printf("\rتست: %,d | یافته: %d", t, f));
        System.out.println();
        if (found.isEmpty()) System.out.println("چیزی یافت نشد.");
        else found.forEach(System.out::println);
    }
    */
                }
