package com.walletrescue;

import android.content.Context;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.MnemonicUtils;
import org.web3j.utils.Numeric;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
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
    private static final int DEFAULT_ADDRESS_COUNT = 100;
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
            report.append("MAINNET RECOVERY CHECK — DERIVED ADDRESSES\n");
            report.append("۱۰۰۰ آدرس اول در هر مسیر مشخص‌شده بررسی می‌شوند.\n");
            report.append("عبارت و کلید خصوصی به اینترنت ارسال نمی‌شوند.\n\n");

            NetworkParameters btcParams = MainNetParams.get();
            DeterministicKey btcMaster = HDKeyDerivation.createMasterPrivateKey(seed);

            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                report.append("BITCOIN — BIP44 (Legacy)\n");
                List<String> legacyAddresses = new ArrayList<>();
                for (int i = 0; i < addressCount; i++) {
                    DeterministicKey key = deriveBitcoin(btcMaster, 44, i);
                    legacyAddresses.add(LegacyAddress.fromKey(btcParams, key).toString());
                }
                appendParallelResults(report, legacyAddresses, AutoWalletScanner::safeBitcoin,
                        pool, progress, cancelled, foundCallback, foundReport, checked, found, addressCount * 4);

                report.append("\nBITCOIN — BIP84 (Native SegWit)\n");
                List<String> segwitAddresses = new ArrayList<>();
                for (int i = 0; i < addressCount; i++) {
                    DeterministicKey key = deriveBitcoin(btcMaster, 84, i);
                    segwitAddresses.add(SegwitAddress.fromKey(btcParams, key).toString());
                }
                appendParallelResults(report, segwitAddresses, AutoWalletScanner::safeBitcoin,
                        pool, progress, cancelled, foundCallback, foundReport, checked, found, addressCount * 4);

                Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);
                report.append("\nETHEREUM — m/44'/60'/0'/0/i\n");
                List<String> ethAddresses = new ArrayList<>();
                for (int i = 0; i < addressCount; i++) {
                    int[] path = {harden(44), harden(60), harden(0), 0, i};
                    Bip32ECKeyPair key = Bip32ECKeyPair.deriveKeyPair(master, path);
                    ethAddresses.add("0x" + Keys.getAddress(key));
                }
                appendParallelResults(report, ethAddresses, AutoWalletScanner::checkEthereum,
                        pool, progress, cancelled, foundCallback, foundReport, checked, found, addressCount * 4);

                report.append("\nTRON — m/44'/195'/0'/0/i\n");
                List<String> tronAddresses = new ArrayList<>();
                for (int i = 0; i < addressCount; i++) {
                    int[] path = {harden(44), harden(195), harden(0), 0, i};
                    Bip32ECKeyPair key = Bip32ECKeyPair.deriveKeyPair(master, path);
                    tronAddresses.add(tronAddress(key));
                }
                appendParallelResults(report, tronAddresses, AutoWalletScanner::checkTron,
                        pool, progress, cancelled, foundCallback, foundReport, checked, found, addressCount * 4);
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
            progress.accept(checked.get() + 1, total);
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
        int marker = line.indexOf("موجودی: ");
        if (marker < 0) return false;
        int start = marker + "موجودی: ".length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
        if (end == start) return false;
        try {
            return new BigDecimal(line.substring(start, end)).compareTo(BigDecimal.ZERO) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean hasActivity(String line) {
        return line.contains("وضعیت فعالیت: فعال");
    }

    private static int harden(int n) {
        return n | Bip32ECKeyPair.HARDENED_BIT;
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
            JSONObject json = getJson("https://mempool.space/api/address/" + address);
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

    private static String checkEthereum(String address) throws Exception {
        return retry(() -> checkEthereumOnce(address));
    }

    private static String checkEthereumOnce(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://ethereum-rpc.publicnode.com").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\","
                    + "\"params\":[\"" + address + "\",\"latest\"],\"id\":1}";
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
            JSONObject json = new JSONObject(readResponse(connection));
            if (json.has("error")) throw new Exception("Ethereum RPC error");
            String hex = json.optString("result", "0x0");
            BigInteger wei = new BigInteger(hex.substring(2), 16);
            BigDecimal eth = new BigDecimal(wei).divide(
                    new BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN);

            String activity = "نامشخص";
            String txCountText = "نامشخص";
            String latest = "";
            try {
                JSONObject details = getJson("https://eth.blockscout.com/api/v2/addresses/" + address);
                long txCount = details.optLong("transactions_count", -1);
                if (txCount >= 0) {
                    txCountText = String.valueOf(txCount);
                    activity = txCount > 0 ? "فعال" : "بدون سابقه تراکنش";
                    if (txCount > 0) {
                        JSONArray txs = getJson("https://eth.blockscout.com/api/v2/addresses/"
                                + address + "/transactions").optJSONArray("items");
                        if (txs != null && txs.length() > 0) {
                            String timestamp = txs.getJSONObject(0).optString("timestamp", "");
                            if (!timestamp.isEmpty()) latest = " | آخرین فعالیت: " + timestamp;
                        }
                    }
                }
            } catch (Exception ignored) { }
            return address + "\nموجودی: " + eth.toPlainString() + " ETH"
                    + " | وضعیت فعالیت: " + activity
                    + " | تعداد تراکنش‌ها: " + txCountText + latest;
        } finally {
            connection.disconnect();
        }
    }

    private static String checkTron(String address) throws Exception {
        JSONObject json = getJson("https://api.trongrid.io/v1/accounts/" + address);
        JSONArray data = json.optJSONArray("data");
        long sun = 0;
        if (data != null && data.length() > 0) {
            sun = data.getJSONObject(0).optLong("balance", 0);
        }
        BigDecimal trx = BigDecimal.valueOf(sun).divide(new BigDecimal("1000000"));
        String activity = "نامشخص";
        String latest = "";
        try {
            JSONObject history = getJson("https://api.trongrid.io/v1/accounts/" + address
                    + "/transactions?only_confirmed=true&limit=1&order_by=block_timestamp,desc");
            JSONArray txs = history.optJSONArray("data");
            if (txs != null) {
                if (txs.length() > 0) {
                    activity = "فعال (دارای سابقه تراکنش)";
                    long timestamp = txs.getJSONObject(0).optLong("block_timestamp", 0);
                    if (timestamp > 0) latest = " | آخرین فعالیت: " + new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(timestamp)) + " UTC";
                } else {
                    activity = "بدون سابقه تراکنش تأییدشده";
                }
            }
        } catch (Exception ignored) { }
        return address + "\nموجودی: " + trx.toPlainString() + " TRX"
                + " | وضعیت فعالیت: " + activity + latest;
    }

    private static String tronAddress(Bip32ECKeyPair key) throws Exception {
        byte[] evm = Numeric.hexStringToByteArray(Keys.getAddress(key));
        byte[] payload = new byte[21];
        payload[0] = 0x41;
        System.arraycopy(evm, 0, payload, 1, 20);
        byte[] first = MessageDigest.getInstance("SHA-256").digest(payload);
        byte[] second = MessageDigest.getInstance("SHA-256").digest(first);
        byte[] full = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(second, 0, full, payload.length, 4);
        return base58(full);
    }

    private static String base58(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        StringBuilder out = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (value.signum() > 0) {
            BigInteger[] div = value.divideAndRemainder(base);
            out.append(BASE58[div[1].intValue()]);
            value = div[0];
        }
        for (byte b : input) {
            if (b == 0) out.append(BASE58[0]);
            else break;
        }
        return out.reverse().toString();
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
