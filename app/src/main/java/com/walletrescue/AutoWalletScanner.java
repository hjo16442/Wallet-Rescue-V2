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
        if (addressCount < 1 || addressCount > 1000) throw new IllegalArgumentException("Address count must be 1-1000.");
        String normalized = phrase.trim().replaceAll("\\s+", " ");
        if (!SafeMnemonicRecovery.isValidCompletePhrase(context, normalized)) {
            throw new Exception("عبارت BIP39 معتبر نیست.");
        }

        byte[] seed = MnemonicUtils.generateSeed(normalized, "");
        StringBuilder report = new StringBuilder();
        int checked = 0;
        int found = 0;

        try {
            report.append("AUTO SCAN — DERIVED ADDRESSES\n");
            report.append("۵ آدرس اول در مسیرهای مشخص‌شده بررسی می‌شوند.\n");
            report.append("عبارت و کلید خصوصی به اینترنت ارسال نمی‌شوند.\n\n");

            NetworkParameters btcParams = MainNetParams.get();
            DeterministicKey btcMaster = HDKeyDerivation.createMasterPrivateKey(seed);

            report.append("BITCOIN — BIP44 (Legacy)\n");
            for (int i = 0; i < ADDRESS_COUNT; i++) {
                DeterministicKey key = deriveBitcoin(btcMaster, 44, i);
                String address = LegacyAddress.fromKey(btcParams, key).toString();
                String line = safeBitcoin(address);
                report.append(line).append("\n");
                checked++;
                if (cancelled.getAsBoolean()) throw new InterruptedException("Cancelled by user");
                progress.accept(checked, addressCount * 4);
                if (line.contains("موجودی:") && !line.contains("موجودی: 0 BTC")) found++;
            }

            report.append("\nBITCOIN — BIP84 (Native SegWit)\n");
            for (int i = 0; i < ADDRESS_COUNT; i++) {
                DeterministicKey key = deriveBitcoin(btcMaster, 84, i);
                String address = SegwitAddress.fromKey(btcParams, key).toString();
                String line = safeBitcoin(address);
                report.append(line).append("\n");
                checked++;
                if (line.contains("موجودی:") && !line.contains("موجودی: 0 BTC")) found++;
            }

            Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);
            report.append("\nETHEREUM — m/44'/60'/0'/0/i\n");
            for (int i = 0; i < ADDRESS_COUNT; i++) {
                try {
                    int[] path = {harden(44), harden(60), harden(0), 0, i};
                    Bip32ECKeyPair key = Bip32ECKeyPair.deriveKeyPair(master, path);
                    String address = "0x" + Keys.getAddress(key);
                    String line = checkEthereum(address);
                    report.append(line).append("\n");
                    if (!line.contains("موجودی: 0 ETH")) found++;
                } catch (Exception e) {
                    report.append("ETH آدرس ").append(i).append(": خطا در بررسی (")
                            .append(e.getMessage()).append(")\n");
                }
                checked++;
            }

            report.append("\nTRON — m/44'/195'/0'/0/i\n");
            for (int i = 0; i < ADDRESS_COUNT; i++) {
                try {
                    int[] path = {harden(44), harden(195), harden(0), 0, i};
                    Bip32ECKeyPair key = Bip32ECKeyPair.deriveKeyPair(master, path);
                    String address = tronAddress(key);
                    String line = checkTron(address);
                    report.append(line).append("\n");
                    if (!line.contains("موجودی: 0 TRX")) found++;
                } catch (Exception e) {
                    report.append("TRON آدرس ").append(i).append(": خطا در بررسی (")
                            .append(e.getMessage()).append(")\n");
                }
                checked++;
            }

            report.append("\nپایان بررسی: ").append(checked).append(" آدرس مشتق‌شده");
            report.append("\nآدرس‌های دارای موجودی غیرصفر: ").append(found);
            report.append("\n\nاین بررسی همه مسیرها یا همه کیف‌پول‌های جهان را پوشش نمی‌دهد.");
            return report.toString();
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
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
            long received = stats == null ? 0 : stats.optLong("funded_txo_sum", 0);
            long spent = stats == null ? 0 : stats.optLong("spent_txo_sum", 0);
            long txCount = stats == null ? 0 : stats.optLong("tx_count", 0);
            BigDecimal balance = BigDecimal.valueOf(received - spent)
                    .divide(new BigDecimal("100000000"));
            return address + "\nموجودی: " + balance.toPlainString()
                    + " BTC | تراکنش تأییدشده: " + txCount;
        } catch (Exception e) {
            return address + "\nخطا در بررسی BTC: " + e.getMessage();
        }
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
            if (json.has("error")) throw new Exception("Ethereum RPC error");
            String hex = json.optString("result", "0x0");
            BigInteger wei = new BigInteger(hex.substring(2), 16);
            BigDecimal eth = new BigDecimal(wei).divide(
                    new BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN);
            return address + "\nموجودی: " + eth.toPlainString() + " ETH";
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
        return address + "\nموجودی: " + trx.toPlainString() + " TRX";
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
