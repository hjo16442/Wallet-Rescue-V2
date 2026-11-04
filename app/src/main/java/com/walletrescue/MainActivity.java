import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * بازیابی عبارت BIP39 (۱۲ یا ۲۴ کلمه) — بدون هیچ وابستگی خارجی
 *
 * نحوهٔ اجرا:
 *   1) فایل bip39.txt (۲۰۴۸ کلمه، هر خط یک کلمه) را کنار این فایل بگذارید.
 *   2) javac MnemonicRecovery.java
 *   3) java MnemonicRecovery
 */
public class MnemonicRecovery {

    // =====================================================================
    //  بارگذاری دیکشنری BIP39 از فایل
    // =====================================================================
    private static List<String> DICT;
    private static String[] DICT_ARR;
    private static Map<String, Integer> WORD_INDEX;

    private static void loadDictionary() throws IOException {
        List<String> list = new ArrayList<>(2048);
        try (BufferedReader br = new BufferedReader(
                new FileReader("bip39.txt", StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) list.add(line);
            }
        }
        if (list.size() != 2048) {
            throw new IOException("bip39.txt باید ۲۰۴۸ کلمه داشته باشد (فعلی: " + list.size() + ")");
        }
        DICT = Collections.unmodifiableList(list);
        DICT_ARR = list.toArray(new String[0]);
        WORD_INDEX = new HashMap<>(4096);
        for (int i = 0; i < DICT_ARR.length; i++) WORD_INDEX.put(DICT_ARR[i], i);
    }

    // =====================================================================
    //  موتور بازیابی
    // =====================================================================
    static class RecoveryEngine {
        private final AtomicLong tested = new AtomicLong(0);
        private volatile boolean cancelled = false;

        void stop() { cancelled = true; }
        long getTested() { return tested.get(); }

        List<String> find(String phrase, boolean forceFull, ProgressCallback cb) throws Exception {
            cancelled = false;
            tested.set(0);

            // نرمال‌سازی
            String norm = (phrase == null) ? "" :
                    Normalizer.normalize(phrase.trim(), Normalizer.Form.NFKD)
                            .toLowerCase(Locale.ROOT)
                            .replace('\u3000', ' ')
                            .replaceAll("\\s+", " ")
                            .trim();
            if (norm.isEmpty())
                norm = String.join(" ", Collections.nCopies(12, "?"));

            String[] words = norm.split(" ");
            int n = words.length;
            if (n != 12 && n != 24)
                throw new Exception("طول باید ۱۲ یا ۲۴ باشد (فعلی: " + n + ")");

            // تحلیل کلمات
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
                            throw new Exception("پیشوند '" + words[i] + "' خیلی کوتاه است (جایگاه " + (i+1) + ")");
                        List<String> pref = new ArrayList<>();
                        for (String w : DICT_ARR) if (w.startsWith(words[i])) pref.add(w);
                        if (pref.isEmpty())
                            throw new Exception("پیشوند '" + words[i] + "' یافت نشد (جایگاه " + (i+1) + ")");
                        if (pref.size() > 1)
                            throw new Exception("پیشوند '" + words[i] + "' مبهم است (" + pref.size() + " گزینه)");
                        idx = WORD_INDEX.get(pref.get(0));
                    }
                    base[i] = idx;
                }
            }

            // حالت ۱: بدون ?
            if (missing.isEmpty()) {
                if (!isValidChecksum(base, n))
                    throw new Exception("checksum رد شد — عبارت معتبر نیست");
                return new ArrayList<>(Collections.singletonList(String.join(" ", words)));
            }

            // حالت ۲: فقط آخرین ? → محاسبهٔ مستقیم
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

            // حالت ۳: جستجو
            boolean fullSearch = missing.size() == n;
            if (fullSearch && !forceFull) {
                throw new Exception(
                        "همهٔ " + n + " کلمه گم شده‌اند (2^" + (n == 12 ? 128 : 256) +
                        " حالت). برای اجرای اجباری، گزینهٔ «جستجوی کامل» را تیک بزنید.");
            }

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
                        for (int v = start; v < end; v++) {
                            if (cancelled) return;
                            local[firstMissing] = v;
                            recurse(local, missing, 1, n, results, cb);
                        }
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
                if (cb != null && (cnt & 0xFFFF) == 0) cb.onProgress(cnt, results.size());
                return;
            }
            int idx = missing.get(pos);
            for (int v = 0; v < 2048; v++) {
                if (cancelled) return;
                work[idx] = v;
                recurse(work, missing, pos + 1, n, results, cb);
            }
        }
    }

    // =====================================================================
    //  checksum (با MessageDigest استاندارد — ساده و مطمئن)
    // =====================================================================
    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(() -> {
                try { return MessageDigest.getInstance("SHA-256"); }
                catch (Exception e) { throw new RuntimeException(e); }
            });

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
        byte[] hash = SHA256.get().digest(entropy);
        int cs = 0;
        for (int i = 0; i < csBits; i++)
            cs = (cs << 1) | ((hash[i >> 3] >> (7 - (i & 7))) & 1);
        return cs;
    }

    @FunctionalInterface
    interface ProgressCallback {
        void onProgress(long tested, long found);
    }

    // =====================================================================
    //  رابط گرافیکی
    // =====================================================================
    private final RecoveryEngine engine = new RecoveryEngine();
    private final JTextArea inputArea  = new JTextArea(3, 60);
    private final JTextArea outputArea = new JTextArea(10, 60);
    private final JCheckBox forceBox   = new JCheckBox("جستجوی کامل (هشدار: هرگز تمام نمی‌شود)");
    private final JButton startBtn     = new JButton("▶ شروع");
    private final JButton stopBtn      = new JButton("■ توقف");
    private final JLabel  statusLabel  = new JLabel("آماده");
    private final JLabel  speedLabel   = new JLabel(" ");

    MnemonicRecovery() {
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

        int missingCount = 0;
        int wordCount = phrase.trim().isEmpty() ? 12 : phrase.trim().split("\\s+").length;
        for (String w : phrase.trim().split("\\s+")) if (w.equals("?")) missingCount++;
        if (phrase.trim().isEmpty()) missingCount = 12;

        if (missingCount == wordCount && wordCount > 0) {
            int c = JOptionPane.showConfirmDialog(null,
                    "همهٔ " + wordCount + " کلمه گم شده‌اند.\n" +
                    "این عملیات از نظر ریاضی هرگز تمام نمی‌شود.\n\n" +
                    "مطمئن هستید؟",
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
        Thread worker = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<String> found = engine.find(phrase, forceFinal, (tested, foundCnt) -> {
                    long elapsed = System.currentTimeMillis() - t0;
                    long speed = elapsed > 0 ? tested * 1000 / elapsed : 0;
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText(String.format("تست: %,d | یافته: %d", tested, foundCnt));
                        speedLabel.setText(String.format("سرعت: %,d تست/ثانیه", speed));
                    });
                });
                SwingUtilities.invokeLater(() -> {
                    if (found.isEmpty()) {
                        statusLabel.setText("تمام شد — چیزی یافت نشد");
                        outputArea.setText("نتیجه‌ای یافت نشد.\nتست‌شده: " + engine.getTested());
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
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("عبارت (کلمات گمشده را با ? بنویسید):"), BorderLayout.NORTH);
        top.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        JPanel topSouth = new JPanel(new BorderLayout());
        topSouth.add(forceBox, BorderLayout.WEST);
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnPanel.add(startBtn);
        btnPanel.add(stopBtn);
        topSouth.add(btnPanel, BorderLayout.EAST);
        top.add(topSouth, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(new JLabel("نتایج:"), BorderLayout.NORTH);
        bottom.add(new JScrollPane(outputArea), BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(speedLabel, BorderLayout.EAST);

        root.add(top, BorderLayout.NORTH);
        root.add(bottom, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        return root;
    }

    public static void main(String[] args) {
        try {
            loadDictionary();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "خطا در بارگذاری bip39.txt:\n" + e.getMessage() +
                    "\n\nفایل bip39.txt باید کنار برنامه باشد و ۲۰۴۸ کلمه داشته باشد.",
                    "خطا", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("بازیابی BIP39 — ۱۲/۲۴ کلمه");
            MnemonicRecovery app = new MnemonicRecovery();
            f.setContentPane(app.buildUI());
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(820, 640);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
