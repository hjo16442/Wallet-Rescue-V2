package com.walletrescue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.bitcoinj.core.Base58;
import org.bouncycastle.util.encoders.Hex;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
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
            if (!validAddress(addr, network)) out.append("آدرس ").append(chain).append(" نامعتبر:\n").append(addr).append("\n------------------\n");
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
        if (network == BTC) return BTC_LEGACY.matcher(a).matches() || BTC_SEGWIT.matcher(a).matches();
        if (network == ETH) return ETH_ADDRESS.matcher(a).matches();
        if (network == TRON) return TRON_ADDRESS.matcher(a).matches();
        return false;
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
            return "Bitcoin: " + address + "\nموجودی تأییدشده: " + c.toPlainString() + " BTC\nدر انتظار تأیید: " + p.toPlainString() + " BTC\nمجموع: " + c.add(p).toPlainString() + " BTC\nتعداد تراکنش‌های تأییدشده: " + chain.optLong("tx_count", 0) + "\nAPI: " + ep;
        } catch (Exception e) { last = e; }
        throw new Exception("APIهای BTC پاسخ ندادند: " + (last == null ? "" : last.getMessage()));
    }

    private static String checkEthereum(String address) throws Exception {
        JSONObject balanceRequest = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_getBalance").put("params", new JSONArray().put(address).put("latest"));
        JSONObject txRequest = new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "eth_getTransactionCount").put("params", new JSONArray().put(address).put("latest"));
        JSONObject balanceResponse = postJson(ETH_RPC, balanceRequest);
        JSONObject txResponse = postJson(ETH_RPC, txRequest);
        if (balanceResponse.has("error") || !balanceResponse.has("result")) throw new Exception("خطای RPC در دریافت موجودی ETH");
        if (txResponse.has("error") || !txResponse.has("result")) throw new Exception("خطای RPC در دریافت تعداد تراکنش‌ها");
        BigInteger wei = new BigInteger(balanceResponse.getString("result").substring(2), 16);
        BigDecimal eth = new BigDecimal(wei).divide(new BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN);
        BigInteger nonce = new BigInteger(txResponse.getString("result").substring(2), 16);
        return "Ethereum: " + address + "\nموجودی ETH: " + eth.stripTrailingZeros().toPlainString() + " ETH\nتعداد تراکنش‌های ارسال‌شده (nonce): " + nonce + "\nمنبع: Ethereum PublicNode RPC";
    }

    private static String checkTron(String address) throws Exception {
        JSONObject accountRequest = new JSONObject().put("address", address).put("visible", true);
        JSONObject account = postJson(TRON_API + "/wallet/getaccount", accountRequest);
        if (account.has("Error")) throw new Exception("TRON API: " + account.optString("Error"));
        long sun = account.optLong("balance", 0L);
        BigDecimal trx = BigDecimal.valueOf(sun).divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);

        byte[] decoded = Base58.decode(address);
        if (decoded.length != 25) throw new Exception("قالب آدرس TRON قابل تبدیل نیست");
        String ownerHex = Hex.toHexString(decoded, 1, 20);
        String parameter = String.format("%024x", new BigInteger(ownerHex, 16));
        JSONObject tokenRequest = new JSONObject()
                .put("owner_address", address)
                .put("contract_address", USDT_TRC20)
                .put("function_selector", "balanceOf(address)")
                .put("parameter", parameter)
                .put("visible", true);
        JSONObject tokenResponse = postJson(TRON_API + "/wallet/triggerconstantcontract", tokenRequest);
        JSONArray values = tokenResponse.optJSONArray("constant_result");
        String usdtText = "نامشخص (پاسخ قرارداد ناقص)";
        if (values != null && values.length() > 0) {
            String hex = values.getString(0);
            BigInteger raw = hex.isEmpty() ? BigInteger.ZERO : new BigInteger(hex, 16);
            BigDecimal usdt = new BigDecimal(raw).divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);
            usdtText = usdt.stripTrailingZeros().toPlainString() + " USDT";
        } else {
            JSONObject contractResult = tokenResponse.optJSONObject("result");
            if (contractResult == null || !contractResult.optBoolean("result", false)) {
                usdtText = "نامشخص (قرارداد پاسخ معتبر نداد)";
            }
        }
        return "TRON: " + address + "\nموجودی TRX: " + trx.stripTrailingZeros().toPlainString() + " TRX\nموجودی USDT-TRC20: " + usdtText + "\nمنبع: TronGrid public API";
    }

    private static BigDecimal sats(long n) {
        return BigDecimal.valueOf(n).divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
    }

    private static JSONObject getJson(String url) throws Exception {
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

    private static String read(InputStream s) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }
}
