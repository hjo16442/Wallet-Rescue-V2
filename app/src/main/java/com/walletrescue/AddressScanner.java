package com.walletrescue;

import org.json.JSONObject;
import org.bitcoinj.core.Address;
import org.bitcoinj.params.MainNetParams;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

public final class AddressScanner {
    public static final int BTC = 0;
    private static final int MAX_ADDRESSES = 100, MAX_WORKERS = 3;
    private static final Pattern BTC_LEGACY = Pattern.compile("^(1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern BTC_SEGWIT = Pattern.compile("^(bc1)[ac-hj-np-z02-9]{11,71}$", Pattern.CASE_INSENSITIVE);

    private AddressScanner() {}
    public interface Progress { void onProgress(int completed, int total); }
    public interface CancelCheck { boolean isCancelled(); }

    public static String scan(MainActivity a, List<String> addresses, int network) throws Exception {
        return scan(a, addresses, network, (d,t)->{}, ()->false);
    }

    public static String scan(MainActivity a, List<String> addresses, int network, Progress progress, CancelCheck cancel) throws Exception {
        if (network != BTC) throw new Exception("فقط شبکه اصلی بیت‌کوین (BTC Mainnet) پشتیبانی می‌شود.");
        if (addresses == null || addresses.isEmpty()) throw new Exception("هیچ آدرسی وارد نشده است.");
        if (addresses.size() > MAX_ADDRESSES) throw new Exception("حداکثر ۱۰۰ آدرس در هر نوبت مجاز است.");

        List<String> valid = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        for (String raw : addresses) {
            String addr = raw == null ? "" : raw.trim();
            if (addr.isEmpty()) continue;
            if (!validAddress(addr, BTC)) out.append("آدرس بیت‌کوین نامعتبر (قالب یا checksum):\n").append(addr).append("\n------------------\n");
            else valid.add(addr);
        }
        if (valid.isEmpty()) return out.append("\nهیچ آدرس معتبر بیت‌کوین وجود ندارد.").toString();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, valid.size()));
        ExecutorCompletionService<String> cs = new ExecutorCompletionService<>(pool);
        try {
            for (String addr : valid) cs.submit(() -> {
                if (cancel.isCancelled()) return addr + "\nبررسی متوقف شد.";
                try { return checkBitcoin(addr); }
                catch (Exception e) { return addr + "\nموجودی نامشخص؛ خطا: " + (e.getMessage() == null ? "خطای شبکه" : e.getMessage()); }
            });
            int done = 0;
            while (done < valid.size()) {
                if (cancel.isCancelled()) break;
                Future<String> f = cs.take();
                out.append(f.get()).append("\n\n------------------\n");
                progress.onProgress(++done, valid.size());
            }
            if (done < valid.size()) out.append("\nبررسی متوقف شد.\n");
            out.append("\nآدرس‌های بررسی‌شده: ").append(done).append(" از ").append(valid.size());
        } finally { pool.shutdownNow(); }
        return out.append("\nاین ابزار فقط اطلاعات عمومی بلاک‌چین بیت‌کوین را می‌خواند و تراکنش انجام نمی‌دهد.").toString();
    }

    static boolean validAddress(String address, int network) {
        if (network != BTC || address == null || address.isEmpty()) return false;
        if (!BTC_LEGACY.matcher(address).matches() && !BTC_SEGWIT.matcher(address).matches()) return false;
        try {
            Address.fromString(MainNetParams.get(), address);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static String checkBitcoin(String address) throws Exception {
        String[] endpoints = {"https://mempool.space/api/address/", "https://blockstream.info/api/address/"};
        Exception last = null;
        for (String ep : endpoints) try {
            JSONObject j = getJson(ep + address);
            JSONObject chain = j.optJSONObject("chain_stats"), mp = j.optJSONObject("mempool_stats");
            if (chain == null || mp == null) throw new Exception("پاسخ ناقص API");
            long confirmed = chain.getLong("funded_txo_sum") - chain.getLong("spent_txo_sum");
            long pending = mp.getLong("funded_txo_sum") - mp.getLong("spent_txo_sum");
            BigDecimal c = sats(confirmed), p = sats(pending);
            long confirmedTx = chain.getLong("tx_count"), pendingTx = mp.getLong("tx_count");
            return "Bitcoin Mainnet: " + address + "\nموجودی تأییدشده: " + c.toPlainString() + " BTC\nدر انتظار تأیید: " + p.toPlainString() + " BTC\nمجموع: " + c.add(p).toPlainString() + " BTC\nتراکنش‌های تأییدشده: " + confirmedTx + "\nتراکنش‌های در انتظار: " + pendingTx + "\nمجموع تراکنش‌های گزارش‌شده: " + (confirmedTx + pendingTx) + "\nAPI: " + ep;
        } catch (Exception e) { last = e; }
        throw new Exception("APIهای بیت‌کوین پاسخ ندادند: " + (last == null ? "" : last.getMessage()));
    }

    private static BigDecimal sats(long n) {
        return BigDecimal.valueOf(n).divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
    }

    private static JSONObject getJson(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { return getJsonOnce(url); }
            catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == 3) break;
                pauseBeforeRetry(attempt);
            }
        }
        throw last == null ? new Exception("خطای نامشخص شبکه") : last;
    }

    private static JSONObject getJsonOnce(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "WalletRescue/3.2");
            int code = c.getResponseCode();
            InputStream s = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (s == null) throw new Exception("HTTP " + code);
            String body = read(s);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
            return new JSONObject(body);
        } finally { c.disconnect(); }
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof IOException) return true;
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.startsWith("HTTP 429") || message.matches("^HTTP 5[0-9]{2}.*");
    }

    private static void pauseBeforeRetry(int attempt) throws Exception {
        try { Thread.sleep(400L * attempt); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("درخواست شبکه متوقف شد.", e);
        }
    }

    private static String read(InputStream s) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }
}
