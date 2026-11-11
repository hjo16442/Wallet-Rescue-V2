package com.walletrescue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Base58;
import org.bitcoinj.params.MainNetParams;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.util.encoders.Hex;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.Arrays;
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
    public static final int BTC = 0, ETH = 1, TRON = 2;
    private static final int MAX_ADDRESSES = 100, MAX_WORKERS = 3;
    private static final Pattern BTC_LEGACY = Pattern.compile("^(1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern BTC_SEGWIT = Pattern.compile("^(bc1)[ac-hj-np-z02-9]{11,71}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETH_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern TRON_ADDRESS = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");
    private static final String ETH_RPC = "https://ethereum-rpc.publicnode.com";
    private static final String TRON_API = "https://api.trongrid.io";
    private static final String USDT_TRC20 = "TXLAQ63Xg1NAzckPwKHvzw7CSEmLMEqcdj";

    private AddressScanner() {}
    public interface Progress { void onProgress(int completed, int total); }
    public interface CancelCheck { boolean isCancelled(); }

    public static String scan(MainActivity a, List<String> addresses, int network) throws Exception {
        return scan(a, addresses, network, (d,t)->{}, ()->false);
    }

    public static String scan(MainActivity a, List<String> addresses, int network, Progress progress, CancelCheck cancel) throws Exception {
        if (addresses == null || addresses.isEmpty()) throw new Exception("هیچ آدرسی وارد نشده است.");
        if (addresses.size() > MAX_ADDRESSES) throw new Exception("حداکثر ۱۰۰ آدرس در هر نوبت مجاز است.");
        if (network < BTC || network > TRON) throw new Exception("شبکه انتخاب‌شده پشتیبانی نمی‌شود.");

        List<String> valid = new ArrayList<>();
        String chain = network == BTC ? "BTC" : network == ETH ? "ETH" : "TRON / TRC20";
        StringBuilder out = new StringBuilder();
        for (String raw : addresses) {
            String addr = raw == null ? "" : raw.trim();
            if (addr.isEmpty()) continue;
            if (!validAddress(addr, network)) out.append("آدرس ").append(chain).append(" نامعتبر (قالب یا checksum):\n").append(addr).append("\n------------------\n");
            else valid.add(addr);
        }
        if (valid.isEmpty()) return out.append("\nهیچ آدرس معتبر ").append(chain).append(" وجود ندارد.").toString();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, valid.size()));
        ExecutorCompletionService<String> cs = new ExecutorCompletionService<>(pool);
        try {
            for (String addr : valid) cs.submit(() -> {
                if (cancel.isCancelled()) return addr + "\nبررسی متوقف شد.";
                try {
                    if (network == BTC) return checkBitcoin(addr);
                    if (network == ETH) return checkEthereum(addr);
                    return checkTron(addr);
                } catch (Exception e) {
                    return addr + "\nموجودی نامشخص؛ خطا: " + (e.getMessage() == null ? "خطای شبکه" : e.getMessage());
                }
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
        } finally {
            pool.shutdownNow();
        }
        return out.append("\nاین ابزار فقط اطلاعات عمومی بلاک‌چین را می‌خواند و تراکنش انجام نمی‌دهد.").toString();
    }

    static boolean validAddress(String a, int network) {
        if (a == null || a.isEmpty()) return false;
        if (network == BTC) return validBitcoinAddress(a);
        if (network == ETH) return validEthereumAddress(a);
        if (network == TRON) return validTronAddress(a);
        return false;
    }

    private static boolean validBitcoinAddress(String address) {
        if (!BTC_LEGACY.matcher(address).matches() && !BTC_SEGWIT.matcher(address).matches()) return false;
        try {
            // bitcoinj validates Base58Check and Bech32 checksums for Bitcoin mainnet.
            Address.fromString(MainNetParams.get(), address);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean validEthereumAddress(String address) {
        if (!ETH_ADDRESS.matcher(address).matches()) return false;
        String body = address.substring(2);
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        String upper = body.toUpperCase(java.util.Locale.ROOT);
        // EIP-55 checksum is optional for uniformly lower- or upper-case addresses.
        if (body.equals(lower) || body.equals(upper)) return true;

        byte[] hash = new byte[32];
        byte[] input = lower.getBytes(StandardCharsets.US_ASCII);
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        digest.doFinal(hash, 0);
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (!Character.isLetter(ch)) continue;
            int nibble = (i & 1) == 0 ? ((hash[i / 2] >>> 4) & 0x0f) : (hash[i / 2] & 0x0f);
            boolean expectedUpper = nibble >= 8;
            if (Character.isUpperCase(ch) != expectedUpper) return false;
        }
        return true;
    }

    private static boolean validTronAddress(String address) {
        if (address == null || !TRON_ADDRESS.matcher(address).matches()) return false;
        try {
            byte[] decoded = Base58.decode(address);
            if (decoded.length != 25 || decoded[0] != 0x41) return false;
            byte[] payload = Arrays.copyOfRange(decoded, 0, 21);
            byte[] first = MessageDigest.getInstance("SHA-256").digest(payload);
            byte[] second = MessageDigest.getInstance("SHA-256").digest(first);
            return Arrays.equals(Arrays.copyOfRange(decoded, 21, 25), Arrays.copyOf(second, 4));
        } catch (Exception ignored) {
            return false;
        }
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
            long confirmedTx = chain.getLong("tx_count");
            long pendingTx = mp.getLong("tx_count");
            return "Bitcoin: " + address + "\nموجودی تأییدشده: " + c.toPlainString() + " BTC\nدر انتظار تأیید: " + p.toPlainString() + " BTC\nمجموع: " + c.add(p).toPlainString() + " BTC\nتراکنش‌های تأییدشده: " + confirmedTx + "\nتراکنش‌های در انتظار: " + pendingTx + "\nمجموع تراکنش‌های گزارش‌شده: " + (confirmedTx + pendingTx) + "\nAPI: " + ep;
        } catch (Exception e) { last = e; }
        throw new Exception("APIهای BTC پاسخ ندادند: " + (last == null ? "" : last.getMessage()));
    }

    private static String checkEthereum(String address) throws Exception {
        JSONObject balanceRequest = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_getBalance").put("params", new JSONArray().put(address).put("latest"));
        JSONObject txRequest = new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "eth_getTransactionCount").put("params", new JSONArray().put(address).put("latest"));
        JSONObject balanceResponse = postJson(ETH_RPC, balanceRequest);
        JSONObject txResponse = postJson(ETH_RPC, txRequest);
        if (balanceResponse.has("error") || !balanceResponse.has("result")) throw new Exception("خطای RPC در دریافت موجودی ETH");
        if (txResponse.has("error") || !txResponse.has("result")) throw new Exception("خطای RPC در دریافت nonce اتریوم");
        BigInteger wei = parseRpcQuantity(balanceResponse.getString("result"), "موجودی ETH");
        BigDecimal eth = new BigDecimal(wei).divide(new BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN);
        BigInteger nonce = parseRpcQuantity(txResponse.getString("result"), "nonce اتریوم");
        return "Ethereum: " + address + "\nموجودی ETH: " + eth.stripTrailingZeros().toPlainString() + " ETH\nnonce: " + nonce + " (تعداد تراکنش‌های ارسال‌شده؛ تاریخچه کامل تراکنش‌ها نیست)\nمنبع: Ethereum PublicNode RPC";
    }

    private static BigInteger parseRpcQuantity(String value, String field) throws Exception {
        if (value == null || !value.matches("^0x[0-9a-fA-F]+$")) throw new Exception("پاسخ نامعتبر RPC برای " + field);
        return new BigInteger(value.substring(2), 16);
    }

    private static String checkTron(String address) throws Exception {
        JSONObject accountRequest = new JSONObject().put("address", address).put("visible", true);
        JSONObject account = postJson(TRON_API + "/wallet/getaccount", accountRequest);
        if (account.has("Error")) throw new Exception("TRON API: " + account.optString("Error"));
        boolean accountActivated = account.length() > 0;
        long sun;
        if (!accountActivated) {
            // TronGrid may return an empty object for an address that has never been activated.
            sun = 0L;
        } else {
            if (!account.has("balance")) throw new Exception("پاسخ حساب TRON ناقص است؛ فیلد balance وجود ندارد.");
            sun = account.getLong("balance");
        }
        BigDecimal trx = BigDecimal.valueOf(sun).divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);

        byte[] decoded = Base58.decode(address);
        String ownerHex = Hex.toHexString(decoded, 1, 20);
        String parameter = "000000000000000000000000" + ownerHex;
        JSONObject tokenRequest = new JSONObject()
                .put("owner_address", address)
                .put("contract_address", USDT_TRC20)
                .put("function_selector", "balanceOf(address)")
                .put("parameter", parameter)
                .put("visible", true);
        JSONObject tokenResponse = postJson(TRON_API + "/wallet/triggerconstantcontract", tokenRequest);
        JSONObject contractStatus = tokenResponse.optJSONObject("result");
        if (contractStatus == null || !contractStatus.has("result")) throw new Exception("پاسخ قرارداد USDT ناقص است.");
        if (!contractStatus.getBoolean("result")) {
            String reason = contractStatus.optString("message", "قرارداد پاسخ موفق نداد");
            throw new Exception("خطای قرارداد USDT: " + reason);
        }
        JSONArray values = tokenResponse.optJSONArray("constant_result");
        if (values == null || values.length() == 0) throw new Exception("پاسخ موجودی USDT فاقد constant_result است.");
        String hex = values.getString(0);
        if (hex == null || !hex.matches("^[0-9a-fA-F]+$")) throw new Exception("مقدار موجودی USDT در پاسخ قرارداد نامعتبر است.");
        BigInteger raw = new BigInteger(hex, 16);
        BigDecimal usdt = new BigDecimal(raw).divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);
        String accountState = accountActivated ? "فعال" : "فعال‌نشده (پاسخ حساب خالی؛ موجودی TRX صفر گزارش شد)";
        return "TRON: " + address + "\nوضعیت حساب: " + accountState + "\nموجودی TRX: " + trx.stripTrailingZeros().toPlainString() + " TRX\nموجودی USDT-TRC20: " + usdt.stripTrailingZeros().toPlainString() + " USDT\nمنبع: TronGrid public API";
    }

    private static BigDecimal sats(long n) {
        return BigDecimal.valueOf(n).divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
    }

    private static JSONObject getJson(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return getJsonOnce(url);
            } catch (Exception e) {
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
            c.setRequestProperty("User-Agent", "WalletRescue/3.1");
            int code = c.getResponseCode();
            InputStream s = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (s == null) throw new Exception("HTTP " + code);
            String body = read(s);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
            return new JSONObject(body);
        } finally { c.disconnect(); }
    }

    private static JSONObject postJson(String url, JSONObject payload) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return postJsonOnce(url, payload);
            } catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == 3) break;
                pauseBeforeRetry(attempt);
            }
        }
        throw last == null ? new Exception("خطای نامشخص شبکه") : last;
    }

    private static JSONObject postJsonOnce(String url, JSONObject payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "WalletRescue/3.1");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            int code = c.getResponseCode();
            InputStream s = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (s == null) throw new Exception("HTTP " + code);
            String body = read(s);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
            JSONObject response = new JSONObject(body);
            if (response.has("Error")) throw new Exception(response.optString("Error"));
            return response;
        } finally { c.disconnect(); }
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof IOException) return true;
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.startsWith("HTTP 429") || message.matches("^HTTP 5[0-9]{2}.*");
    }

    private static void pauseBeforeRetry(int attempt) throws Exception {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
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
