package com.walletrescue;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.List;
import java.util.regex.Pattern;

public class AddressScanner {

    private static final int MAX_ADDRESSES = 20;

    private static final Pattern BTC_LEGACY =
            Pattern.compile("^(1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern BTC_SEGWIT =
            Pattern.compile("^(bc1)[ac-hj-np-z02-9]{11,71}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETH =
            Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern TRON =
            Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");

    private static final char[] BASE58 =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private AddressScanner() {}

    public static String scan(
            MainActivity activity,
            List<String> addresses,
            int network
    ) throws Exception {

        if (addresses == null || addresses.isEmpty()) {
            throw new Exception("هیچ آدرسی وارد نشده است.");
        }

        if (addresses.size() > MAX_ADDRESSES) {
            throw new Exception("حداکثر ۲۰ آدرس در هر نوبت مجاز است.");
        }

        if (network < 0 || network > 2) {
            throw new Exception("شبکه نامعتبر است.");
        }

        StringBuilder output = new StringBuilder();
        int checked = 0;

        for (String item : addresses) {
            String address = item == null ? "" : item.trim();
            if (address.isEmpty()) continue;

            if (!validAddress(address, network)) {
                output.append("آدرس نامعتبر یا با قالب نادرست:\n")
                      .append(address)
                      .append("\n------------------\n");
                continue;
            }

            try {
                String result;
                if (network == 0) {
                    result = checkBitcoin(address);
                } else if (network == 1) {
                    result = checkEthereum(address);
                } else {
                    result = checkTron(address);
                }

                output.append(result)
                      .append("\n\n------------------\n");
                checked++;
            } catch (Exception e) {
                output.append(address)
                      .append("\nخطا در دریافت اطلاعات: ")
                      .append(e.getMessage())
                      .append("\n------------------\n");
            }
        }

        output.append("\nتعداد آدرس‌های بررسی‌شده: ").append(checked);
        output.append("\nاین گزارش فقط اطلاعات عمومی بلاک‌چین را می‌خواند؛");
        output.append(" هیچ تراکنش یا انتقالی انجام نمی‌دهد.");
        return output.toString();
    }

    private static boolean validAddress(String address, int network) {
        if (network == 0) {
            return BTC_LEGACY.matcher(address).matches()
                    || BTC_SEGWIT.matcher(address).matches();
        }
        if (network == 1) {
            return ETH.matcher(address).matches();
        }
        if (network == 2) {
            if (!TRON.matcher(address).matches()) return false;
            try {
                byte[] decoded = decodeBase58(address);
                if (decoded.length != 25 || decoded[0] != 0x41) return false;
                byte[] payload = Arrays.copyOf(decoded, 21);
                byte[] first = MessageDigest.getInstance("SHA-256").digest(payload);
                byte[] second = MessageDigest.getInstance("SHA-256").digest(first);
                for (int i = 0; i < 4; i++) {
                    if (decoded[21 + i] != second[i]) return false;
                }
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static String checkBitcoin(String address) throws Exception {
        JSONObject json = getJson("https://mempool.space/api/address/" + address);
        JSONObject chain = json.optJSONObject("chain_stats");
        JSONObject mempool = json.optJSONObject("mempool_stats");

        long received = chain == null ? 0 : chain.optLong("funded_txo_sum", 0);
        long spent = chain == null ? 0 : chain.optLong("spent_txo_sum", 0);
        long pendingReceived = mempool == null ? 0 : mempool.optLong("funded_txo_sum", 0);
        long pendingSpent = mempool == null ? 0 : mempool.optLong("spent_txo_sum", 0);
        long txCount = chain == null ? 0 : chain.optLong("tx_count", 0);
        long balanceSats = received - spent + pendingReceived - pendingSpent;

        BigDecimal btc = BigDecimal.valueOf(balanceSats)
                .divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
        return "Bitcoin: " + address
                + "\nموجودی: " + btc.toPlainString() + " BTC"
                + "\nتعداد تراکنش‌های تأییدشده: " + txCount;
    }

    private static String checkEthereum(String address) throws Exception {
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
            if (json.has("error")) {
                throw new Exception("پاسخ خطای سرویس اتریوم");
            }

            String hex = json.optString("result", "0x0");
            if (!hex.startsWith("0x") || hex.length() < 3) {
                throw new Exception("پاسخ نامعتبر از سرویس اتریوم");
            }

            BigInteger wei = new BigInteger(hex.substring(2), 16);
            BigDecimal eth = new BigDecimal(wei).divide(
                    new BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN);

            return "Ethereum: " + address + "\nموجودی: " + eth.toPlainString() + " ETH";
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

        BigDecimal trx = BigDecimal.valueOf(sun)
                .divide(new BigDecimal("1000000"), 6, RoundingMode.DOWN);
        return "TRON: " + address + "\nموجودی: " + trx.toPlainString() + " TRX";
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            return new JSONObject(readResponse(connection));
        } finally {
            connection.disconnect();
        }
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

    private static byte[] decodeBase58(String value) throws Exception {
        BigInteger number = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit = -1;
            for (int j = 0; j < BASE58.length; j++) {
                if (BASE58[j] == c) {
                    digit = j;
                    break;
                }
            }
            if (digit < 0) throw new Exception("Base58 نامعتبر");
            number = number.multiply(base).add(BigInteger.valueOf(digit));
        }

        byte[] raw = number.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }

        int leadingZeros = 0;
        while (leadingZeros < value.length() && value.charAt(leadingZeros) == '1') {
            leadingZeros++;
        }

        byte[] decoded = new byte[leadingZeros + raw.length];
        System.arraycopy(raw, 0, decoded, leadingZeros, raw.length);
        return decoded;
    }
}
