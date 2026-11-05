package com.walletrescue;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

public class AddressScanner {

    private static final int MAX_ADDRESSES = 100;
    private static final int MAX_WORKERS = 4;
    private static final int MAX_ATTEMPTS = 3;

    private static final Pattern BTC_LEGACY =
            Pattern.compile("^(1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern BTC_SEGWIT =
            Pattern.compile("^(bc1)[ac-hj-np-z02-9]{11,71}$", Pattern.CASE_INSENSITIVE);

    private static final char[] BASE58 =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private interface Request<T> {
        T run() throws Exception;
    }

    private AddressScanner() {}

    public interface Progress {
        void onProgress(int completed, int total);
    }

    public static String scan(
            MainActivity activity,
            List<String> addresses,
            int network
    ) throws Exception {
        return scan(activity, addresses, network, (done, total) -> {}, () -> false);
    }

    public interface CancelCheck {
        boolean isCancelled();
    }

    public static String scan(
            MainActivity activity,
            List<String> addresses,
            int network,
            Progress progress,
            CancelCheck cancelCheck
    ) throws Exception {
        if (addresses == null || addresses.isEmpty()) {
            throw new Exception("هیچ آدرسی وارد نشده است.");
        }
        if (addresses.size() > MAX_ADDRESSES) {
            throw new Exception("حداکثر ۱۰۰ آدرس در هر نوبت مجاز است.");
        }
        if (network != 0) {
            throw new Exception("شبکه نامعتبر است.");
        }

        List<String> valid = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        for (String item : addresses) {
            String address = item == null ? "" : item.trim();
            if (address.isEmpty()) continue;
            if (!validAddress(address, network)) {
                output.append("آدرس نامعتبر یا با قالب نادرست:\\n")
                        .append(address).append("\\n------------------\\n");
            } else {
                valid.add(address);
            }
        }
        if (valid.isEmpty()) {
            output.append("\\nهیچ آدرس معتبری برای بررسی وجود نداشت.");
            return output.toString();
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, valid.size()));
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(pool);
        try {
            for (String address : valid) {
                completion.submit(() -> {
                    if (cancelCheck.isCancelled()) return address + "\\nوضعیت: بررسی متوقف شد";
                    try {
                        return checkBitcoin(address);
                    } catch (Exception e) {
                        return address + "\\nوضعیت: دریافت اطلاعات ناموفق؛ موجودی نامشخص است"
                                + "\\nجزئیات خطا: "
                                + (e.getMessage() == null ? "خطای ارتباط با سرویس" : e.getMessage());
                    }
                });
            }

            int done = 0;
            while (done < valid.size()) {
                if (cancelCheck.isCancelled()) break;
                Future<String> future = completion.take();
                output.append(future.get()).append("\\n\\n------------------\\n");
                done++;
                progress.onProgress(done, valid.size());
            }
            if (done < valid.size()) {
                output.append("\\nبررسی به درخواست کاربر متوقف شد.\\n");
            }
            output.append("\\nتعداد آدرس‌های معتبر بررسی‌شده: ").append(done)
                    .append(" از ").append(valid.size());
        } finally {
            pool.shutdownNow();
        }

        output.append("\\nاین گزارش فقط اطلاعات عمومی بلاک‌چین را می‌خواند؛")
                .append(" هیچ تراکنش یا انتقالی انجام نمی‌دهد.");
        return output.toString();
    }

    private static boolean validAddress(String address, int network) {
        if (network == 0) {
            return BTC_LEGACY.matcher(address).matches()
                    || BTC_SEGWIT.matcher(address).matches();
        }
        return false;
    }

    private static String checkBitcoin(String address) throws Exception {
        JSONObject json = retry(() -> getJsonOnce("https://mempool.space/api/address/" + address));
        JSONObject chain = json.optJSONObject("chain_stats");
        JSONObject mempool = json.optJSONObject("mempool_stats");
        if (chain == null || mempool == null) {
            throw new Exception("پاسخ ناقص از سرویس بیت‌کوین؛ موجودی قابل تأیید نیست");
        }

        long received = chain.getLong("funded_txo_sum");
        long spent = chain.getLong("spent_txo_sum");
        long pendingReceived = mempool.getLong("funded_txo_sum");
        long pendingSpent = mempool.getLong("spent_txo_sum");
        long txCount = chain.optLong("tx_count", 0);

        long confirmedSats = received - spent;
        long pendingSats = pendingReceived - pendingSpent;
        long totalSats = confirmedSats + pendingSats;

        BigDecimal confirmed = satsToBtc(confirmedSats);
        BigDecimal pending = satsToBtc(pendingSats);
        BigDecimal total = satsToBtc(totalSats);

        return "Bitcoin: " + address
                + "\nموجودی تأییدشده: " + confirmed.toPlainString() + " BTC"
                + "\nموجودی در انتظار تأیید: " + pending.toPlainString() + " BTC"
                + "\nمجموع موجودی: " + total.toPlainString() + " BTC"
                + "\nتعداد تراکنش‌های تأییدشده: " + txCount;
    }

    private static BigDecimal satsToBtc(long sats) {
        return BigDecimal.valueOf(sats)
                .divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
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

    private static <T> T retry(Request<T> request) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return request.run();
            } catch (Exception e) {
                lastError = e;
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) break;
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new Exception("بررسی به درخواست توقف واکنش نشان داد", interrupted);
                }
            }
        }
        throw lastError == null ? new Exception("خطای نامشخص شبکه") : lastError;
    }

    private static boolean isRetryable(Exception error) {
        String message = error.getMessage();
        if (message == null) return true;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("http 4") && !lower.contains("http 429")) return false;
        return lower.contains("http 429")
                || lower.contains("http 5")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("connection")
                || lower.contains("reset")
                || lower.contains("temporarily")
                || lower.contains("failed to connect")
                || lower.contains("unexpected end");
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) throw new Exception("HTTP " + code);

        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }

        if (code >= 400) {
            throw new Exception("HTTP " + code + " از سرویس شبکه");
        }
        return out.toString();
    }

}
