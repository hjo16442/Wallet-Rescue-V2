package com.walletrescue;

import android.content.Context;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.json.JSONObject;
import org.web3j.crypto.MnemonicUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

public final class AutoWalletScanner {
    private static final int DEFAULT_ADDRESS_COUNT = 100;\n    private static final int MAX_WORKERS = 12;
    private static final char[] BASE58 =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private AutoWalletScanner() {}

    public static String scan(Context context, String phrase) throws Exception {
        return scan(context, phrase, DEFAULT_ADDRESS_COUNT);
    }

    public static String scan(Context context, String phrase, int addressCount) throws Exception {
        return scan(context, phrase, addressCount, (done, total) -> {}, () -> false);
    }

    public static String scan(Context context, String phrase, int addressCount,
                              BiConsumer<Integer, Integer> progress,
                              BooleanSupplier cancelled) throws Exception {
        return scan(context, phrase, addressCount, progress, cancelled, line -> {});
    }

    public static String scan(Context context, String phrase, int addressCount,
                              BiConsumer<Integer, Integer> progress,
                              BooleanSupplier cancelled,
                              Consumer<String> foundCallback) throws Exception {
        if (addressCount < 1 || addressCount > 1000) throw new IllegalArgumentException("Address count must be 1-1000.");
        String normalized = phrase.trim().replaceAll("\\s+", " ");
        if (!SafeMnemonicRecovery.isValidCompletePhrase(context, normalized)) {
            throw new Exception("عبارت BIP39 معتبر نیست.");
        }

        byte[] seed = MnemonicUtils.generateSeed(normalized, "");
        StringBuilder report = new StringBuilder();
        StringBuilder foundReport = new StringBuilder();
        AtomicInteger checked = new AtomicInteger(0);
        AtomicInteger found = new AtomicInteger(0);

        try {
            report.append("BITCOIN MAINNET RECOVERY CHECK — DERIVED ADDRESSES\n");
            report.append("تا ۱۰۰۰ آدرس اول در هر یک از دو مسیر بیت‌کوین بررسی می‌شوند.\n");
            report.append("عبارت و کلید خصوصی به اینترنت ارسال نمی‌شوند.\n\n");

            NetworkParameters btcParams = MainNetParams.get();
            DeterministicKey btcMaster = HDKeyDerivation.createMasterPrivateKey(seed);

            ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
            try {
                report.append("BITCOIN — BIP44 (Legacy)\n");
                List<String> legacyAddresses = new ArrayList<>();
                for (int i = 0; i < addressCount; i++) {
                    DeterministicKey key = deriveBitcoin(btcMaster, 44, i);
                    legacyAddresses.add(LegacyAddress.fromKey(btcParams, key).toString());
                }
                appendParallelResults(report, legacyAddresses, AutoWalletScanner::safeBitcoin,
                        pool, progress, cancelled, foundCallback, foundReport, checked, found, addressCount * 2);

                report.append("\nBITCOIN — BIP84 (Native SegWit)\n");
                List<String> segwitAddresses = new ArrayList<>();
                for (int i = 0; i < addressCount; i++) {
                    DeterministicKey key = deriveBitcoin(btcMaster, 84, i);
                    segwitAddresses.add(SegwitAddress.fromKey(btcParams, key).toString());
                }
                appendParallelResults(report, segwitAddresses, AutoWalletScanner::safeBitcoin,
                        pool, progress, cancelled, foundCallback, foundReport, checked, found, addressCount * 3);

            } finally {
                pool.shutdownNow();
            }

            if (found.get() > 0) {
                report.insert(0, "🎉 آدرس‌های دارای موجودی (اولویت نمایش)\n\n" + foundReport + "\n--------------------\n\n");
            }
            report.append("\nپایان بررسی: ").append(checked).append(" آدرس مشتق‌شده");
            report.append("\nآدرس‌های دارای موجودی غیرصفر: ").append(found);
            report.append("\n\nاین بررسی همه مسیرها یا همه کیف‌پول‌های جهان را پوشش نمی‌دهد.");
            return report.toString();
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }

    private static void appendParallelResults(
            StringBuilder report, List<String> addresses, CallableCheck checker,
            ExecutorService pool, BiConsumer<Integer, Integer> progress,
            BooleanSupplier cancelled, Consumer<String> foundCallback,
            StringBuilder foundReport, AtomicInteger checked, AtomicInteger found,
            int total) throws Exception {
        List<Future<String>> futures = new ArrayList<>();
        for (String address : addresses) {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Cancelled");
            futures.add(pool.submit(() -> {
                String line;
                try {
                    line = checker.check(address);
                } catch (Exception e) {
                    line = address + "\nخطا در بررسی: " + e.getMessage();
                }
                if (hasPositiveBalance(line) || hasActivity(line)) {
                    found.incrementAndGet();
                    synchronized (foundReport) { foundReport.append(line).append("\n\n"); }
                    foundCallback.accept(line);
                }
                int done = checked.incrementAndGet();
                progress.accept(done, total);
                return line;
            }));
        }
        for (Future<String> future : futures) {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Cancelled");
            report.append(future.get()).append("\n");
        }
    }

    @FunctionalInterface
    private interface CallableCheck {
        String check(String address) throws Exception;
    }

    private static boolean hasPositiveBalance(String line) {
        // BTC uses a separate confirmed-balance label; include it in positive-result detection.
        String[] markers = {"موجودی: ", "موجودی تأییدشده: ", "موجودی در انتظار: "};
        for (String marker : markers) {
            int from = 0;
            while (true) {
                int markerAt = line.indexOf(marker, from);
                if (markerAt < 0) break;
                int start = markerAt + marker.length();
                int end = start;
                while (end < line.length()
                        && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
                if (end > start) {
                    try {
                        if (new BigDecimal(line.substring(start, end)).compareTo(BigDecimal.ZERO) > 0) {
                            return true;
                        }
                    } catch (NumberFormatException ignored) { }
                }
                from = start;
            }
        }
        return false;
    }

    private static boolean hasActivity(String line) {
        return line.contains("وضعیت فعالیت: فعال");
    }

    private static DeterministicKey deriveBitcoin(
            DeterministicKey master, int purpose, int index) {
        DeterministicKey key = HDKeyDerivation.deriveChildKey(master, new ChildNumber(purpose, true));
        key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(0, true));
        key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(0, true));
        key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(0, false));
        return HDKeyDerivation.deriveChildKey(key, new ChildNumber(index, false));
    }

    private static String safeBitcoin(String address) {
        try {
            JSONObject json;
            String primaryError = "";
            try {
                json = getJsonFast("https://mempool.space/api/address/" + address);
            } catch (Exception first) {
                primaryError = String.valueOf(first.getMessage());
                try {
                    json = getJsonFast("https://blockstream.info/api/address/" + address);
                } catch (Exception second) {
                    throw new Exception("سرویس‌های BTC در دسترس نیستند؛ mempool: "
                            + primaryError + "؛ blockstream: " + second.getMessage());
                }
            }
            JSONObject stats = json.optJSONObject("chain_stats");
            JSONObject mempool = json.optJSONObject("mempool_stats");
            if (stats == null || mempool == null) {
                throw new Exception("پاسخ ناقص از سرویس بیت‌کوین؛ موجودی قابل تأیید نیست");
            }
            long received = stats.optLong("funded_txo_sum", 0);
            long spent = stats.optLong("spent_txo_sum", 0);
            long pendingReceived = mempool.optLong("funded_txo_sum", 0);
            long pendingSpent = mempool.optLong("spent_txo_sum", 0);
            long confirmedTx = stats.optLong("tx_count", 0);
            long pendingTx = mempool.optLong("tx_count", 0);
            BigDecimal confirmed = BigDecimal.valueOf(received - spent)
                    .divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
            BigDecimal pending = BigDecimal.valueOf(pendingReceived - pendingSpent)
                    .divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
            BigDecimal total = confirmed.add(pending);
            long allTx = confirmedTx + pendingTx;
            String activity = allTx > 0 ? "فعال" : "بدون سابقه تراکنش";
            return address + "\nموجودی تأییدشده: " + confirmed.toPlainString()
                    + " BTC | موجودی در انتظار: " + pending.toPlainString()
                    + " BTC | مجموع: " + total.toPlainString()
                    + " BTC | وضعیت فعالیت: " + activity
                    + " | تراکنش تأییدشده: " + confirmedTx
                    + " | در انتظار: " + pendingTx;
        } catch (Exception e) {
            return address + "\nخطا در بررسی BTC: " + e.getMessage();
        }
    }

    private static JSONObject getJsonFast(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            return new JSONObject(readResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        return retry(() -> getJsonOnce(url));
    }

    private static JSONObject getJsonOnce(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            return new JSONObject(readResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    @FunctionalInterface
    private interface RetryableCall<T> {
        T run() throws Exception;
    }

    private static <T> T retry(RetryableCall<T> call) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return call.run();
            } catch (Exception e) {
                last = e;
                if (!isTemporaryError(e) || attempt == 2) throw e;
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last == null ? new Exception("خطای نامشخص شبکه") : last;
    }

    private static boolean isTemporaryError(Exception e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("http 429")
                || message.matches("(?s).*http 5[0-9]{2}.*")
                || message.contains("timeout")
                || message.contains("timed out")
                || message.contains("connection")
                || message.contains("reset")
                || message.contains("temporarily")
                || message.contains("unavailable")
                || message.contains("network");
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) throw new Exception("HTTP " + code);
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        if (code >= 400) throw new Exception("HTTP " + code + ": " + out);
        return out.toString();
    }
}
