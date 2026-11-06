package com.walletrescue;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.json.JSONObject;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.MnemonicUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

public class AddressScanner {

    private static final int MAX_ADDRESSES = 100;
    private static final int MAX_WORKERS = 4;

    private static final Pattern BTC_LEGACY =
            Pattern.compile("^(1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern BTC_SEGWIT =
            Pattern.compile("^(bc1)[ac-hj-np-z02-9]{11,71}$", Pattern.CASE_INSENSITIVE);

    private static final Pattern P2PKH_PATTERN =
            Pattern.compile("^1[a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern P2SH_PATTERN =
            Pattern.compile("^3[a-km-zA-HJ-NP-Z1-9]{25,34}$");

    private static final char[] BASE58 =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private static final String BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] BECH32_GEN = {
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    };

    private AddressScanner() {}

    public interface Progress {
        void onProgress(int completed, int total);
    }

    public interface CancelCheck {
        boolean isCancelled();
    }

    // ==========================================================
    //  (بخش قبلی — بدون تغییر)
    // ==========================================================

    public static String scan(MainActivity activity, List<String> addresses, int network) throws Exception {
        return scan(activity, addresses, network, (done, total) -> {}, () -> false);
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
                output.append("آدرس نامعتبر یا با قالب نادرست:\n")
                        .append(address).append("\n------------------\n");
            } else {
                valid.add(address);
            }
        }
        if (valid.isEmpty()) {
            output.append("\nهیچ آدرس معتبری برای بررسی وجود نداشت.");
            return output.toString();
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, valid.size()));
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(pool);
        try {
            for (String address : valid) {
                completion.submit(() -> {
                    if (cancelCheck.isCancelled()) return address + "\nوضعیت: بررسی متوقف شد";
                    try {
                        return checkBitcoin(address);
                    } catch (Exception e) {
                        return address + "\nوضعیت: دریافت اطلاعات ناموفق؛ موجودی نامشخص است"
                                + "\nجزئیات خطا: "
                                + (e.getMessage() == null ? "خطای ارتباط با سرویس" : e.getMessage());
                    }
                });
            }

            int done = 0;
            while (done < valid.size()) {
                if (cancelCheck.isCancelled()) break;
                Future<String> future = completion.take();
                output.append(future.get()).append("\n\n------------------\n");
                done++;
                progress.onProgress(done, valid.size());
            }
            if (done < valid.size()) {
                output.append("\nبررسی به درخواست کاربر متوقف شد.\n");
            }
            output.append("\nتعداد آدرس‌های معتبر بررسی‌شده: ").append(done)
                    .append(" از ").append(valid.size());
        } finally {
            pool.shutdownNow();
        }

        output.append("\nاین گزارش فقط اطلاعات عمومی بلاک‌چین را می‌خواند؛")
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
        String[] endpoints = {
                "https://mempool.space/api/address/",
                "https://blockstream.info/api/address/"
        };
        Exception lastError = null;
        for (String endpoint : endpoints) {
            try {
                JSONObject json = getJsonOnce(endpoint + address);
                JSONObject chain = json.optJSONObject("chain_stats");
                JSONObject mempool = json.optJSONObject("mempool_stats");
                if (chain == null || mempool == null) {
                    throw new Exception("پاسخ ناقص از سرویس بیت‌کوین");
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
                        + "\nتعداد تراکنش‌های تأییدشده: " + txCount
                        + "\nمنبع: " + endpoint;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new Exception("هر دو سرویس بررسی بیت‌کوین در دسترس نبودند. "
                + (lastError == null ? "" : lastError.getMessage()));
    }

    private static BigDecimal satsToBtc(long sats) {
        return BigDecimal.valueOf(sats)
                .divide(new BigDecimal("100000000"), 8, RoundingMode.DOWN);
    }

    private static JSONObject getJsonOnce(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "WalletRescue/2.8");
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

    // ==========================================================
    //  تطبیق عبارت بازیابی با آدرس هدف (بیت‌کوین)
    // ==========================================================

    public interface MatchProgress {
        void onProgress(int checked, int total, String currentPath);
    }

    public static class MatchResult {
        public boolean matched;
        public String mnemonic;
        public String targetAddress;
        public String matchedAddress;
        public String derivationPath;
        public String addressType;
        public int totalChecked;
        public String error;

        @Override
        public String toString() {
            if (error != null) return "خطا: " + error;
            if (!matched) {
                return "عبارت بازیابی معتبر بود اما آدرس مشتق‌شده با آدرس هدف تطبیق نداشت.\n"
                        + "آدرس هدف: " + targetAddress + "\n"
                        + "تعداد کلیدهای بررسی‌شده: " + totalChecked;
            }
            return "✅ تطبیق پیدا شد!\n"
                    + "آدرس هدف: " + targetAddress + "\n"
                    + "آدرس مشتق‌شده: " + matchedAddress + "\n"
                    + "مسیر مشتق‌سازی: " + derivationPath + "\n"
                    + "نوع آدرس: " + addressType + "\n"
                    + "تعداد کلیدهای بررسی‌شده تا تطبیق: " + totalChecked;
        }
    }

    /**
     * عبارت بازیابی را اعتبارسنجی می‌کند، آدرس عمومی را با مسیر درست
     * (بر اساس نوع آدرس هدف) می‌سازد و با آدرس هدف مقایسه می‌کند.
     *
     * @param mnemonic        عبارت بازیابی BIP39
     * @param targetAddress   آدرس BTC هدف
     * @param maxIndexPerChain تعداد ایندکس در هر زنجیره (خارجی/تغییر)
     * @param progress        اختیاری، برای گزارش پیشرفت
     * @param cancelCheck     اختیاری، برای توقف
     */
    public static MatchResult matchMnemonicAgainstAddress(
            String mnemonic,
            String targetAddress,
            int maxIndexPerChain,
            MatchProgress progress,
            CancelCheck cancelCheck) {

        MatchResult result = new MatchResult();
        result.mnemonic = mnemonic;
        result.targetAddress = targetAddress;

        // ۱) اعتبارسنجی عبارت بازیابی
        String normalized = mnemonic == null ? ""
                : mnemonic.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            result.error = "عبارت بازیابی وارد نشده است.";
            return result;
        }
        try {
            if (!MnemonicUtils.validateMnemonic(normalized)) {
                result.error = "عبارت بازیابی نامعتبر است یا checksum استاندارد BIP39 را ندارد.";
                return result;
            }
        } catch (Exception e) {
            result.error = "اعتبارسنجی عبارت بازیابی ناموفق بود: " + e.getMessage();
            return result;
        }

        // ۲) اعتبارسنجی آدرس هدف
        if (targetAddress == null || targetAddress.trim().isEmpty()) {
            result.error = "آدرس هدف وارد نشده است.";
            return result;
        }
        String target = targetAddress.trim();
        int type = detectBtcAddressType(target);
        if (type < 0) {
            result.error = "قالب آدرس هدف پشتیبانی نمی‌شود. "
                    + "آدرس باید BTC legacy (1…)، P2SH (3…) یا bech32 (bc1q…) باشد.";
            return result;
        }
        int purpose = (type == 0) ? 44 : (type == 1) ? 49 : 84;
        String typeName = (type == 0) ? "P2PKH (BIP44)"
                : (type == 1) ? "P2SH-P2WPKH (BIP49)"
                : "P2WPKH (BIP84)";
        result.addressType = typeName;

        if (maxIndexPerChain <= 0) maxIndexPerChain = 20;

        int chains = 2;
        int total = maxIndexPerChain * chains;
        int checked = 0;

        try {
            // ۳) تولید seed و master key
            byte[] seed = MnemonicUtils.generateSeed(normalized, "");
            Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);

            // ۴) پیمایش مسیرها و مقایسه با آدرس هدف
            for (int chain = 0; chain <= 1; chain++) {
                for (int idx = 0; idx < maxIndexPerChain; idx++) {
                    if (cancelCheck != null && cancelCheck.isCancelled()) {
                        result.error = "بررسی به درخواست کاربر متوقف شد.";
                        result.totalChecked = checked;
                        return result;
                    }

                    int[] path = new int[]{
                            purpose | Bip32ECKeyPair.HARDENED_BIT,
                            0 | Bip32ECKeyPair.HARDENED_BIT,
                            0 | Bip32ECKeyPair.HARDENED_BIT,
                            chain,
                            idx
                    };
                    Bip32ECKeyPair child = Bip32ECKeyPair.deriveKeyPair(master, path);
                    String derived = deriveBtcAddress(child, type);
                    checked++;
                    if (progress != null) {
                        progress.onProgress(checked, total,
                                "m/" + purpose + "'/0'/0'/" + chain + "/" + idx);
                    }

                    if (derived != null && derived.equals(target)) {
                        result.matched = true;
                        result.matchedAddress = derived;
                        result.derivationPath =
                                "m/" + purpose + "'/0'/0'/" + chain + "/" + idx;
                        result.totalChecked = checked;
                        return result;
                    }
                }
            }

            // ۵) بدون تطبیق
            result.matched = false;
            result.totalChecked = checked;
            return result;
        } catch (Exception e) {
            result.error = "خطا در اشتقاق آدرس: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
            result.totalChecked = checked;
            return result;
        }
    }

    /** نسخهٔ راحت برای فراخوانی از UI: خروجی متنی کامل. */
    public static String matchMnemonicToString(
            String mnemonic, String targetAddress, int maxIndexPerChain,
            MatchProgress progress, CancelCheck cancelCheck) {
        return matchMnemonicAgainstAddress(
                mnemonic, targetAddress, maxIndexPerChain, progress, cancelCheck
        ).toString();
    }

    private static int detectBtcAddressType(String addr) {
        if (addr == null || addr.isEmpty()) return -1;
        String lower = addr.toLowerCase(Locale.ROOT);
        if (P2PKH_PATTERN.matcher(addr).matches()) return 0;
        if (P2SH_PATTERN.matcher(addr).matches()) return 1;
        if (lower.startsWith("bc1q")
                && lower.length() >= 42 && lower.length() <= 50
                && BTC_SEGWIT.matcher(addr).matches()) {
            return 2;
        }
        return -1;
    }

    private static String deriveBtcAddress(Bip32ECKeyPair child, int type) {
        try {
            byte[] xy = pubKeyToXY(child.getPublicKey());
            byte[] compressed = compressPubKey(xy);
            switch (type) {
                case 0: return p2pkhAddress(compressed);
                case 1: return p2shP2wpkhAddress(compressed);
                case 2: return p2wpkhAddress(compressed);
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** تبدیل BigInteger کلید عمومی به X||Y (۶۴ بایت). */
    private static byte[] pubKeyToXY(BigInteger pubKey) {
        byte[] raw = pubKey.toByteArray();
        if (raw.length > 64 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        if (raw.length == 65) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        byte[] out = new byte[64];
        int copyLen = Math.min(raw.length, 64);
        System.arraycopy(raw, raw.length - copyLen, out, 64 - copyLen, copyLen);
        return out;
    }

    /** X||Y → کلید فشرده (۳۳ بایت با پیشوند ۰۲/۰۳). */
    private static byte[] compressPubKey(byte[] xy) {
        byte[] out = new byte[33];
        out[0] = ((xy[63] & 1) == 0) ? (byte) 0x02 : (byte) 0x03;
        System.arraycopy(xy, 0, out, 1, 32);
        return out;
    }

    private static String p2pkhAddress(byte[] compressedPub) throws Exception {
        byte[] hash = ripemd160(sha256(compressedPub));
        byte[] payload = new byte[21];
        payload[0] = 0x00;
        System.arraycopy(hash, 0, payload, 1, 20);
        return base58check(payload);
    }

    private static String p2shP2wpkhAddress(byte[] compressedPub) throws Exception {
        byte[] pubHash = ripemd160(sha256(compressedPub));
        byte[] redeemScript = new byte[22];
        redeemScript[0] = 0x00;
        redeemScript[1] = 0x14;
        System.arraycopy(pubHash, 0, redeemScript, 2, 20);
        byte[] scriptHash = ripemd160(sha256(redeemScript));
        byte[] payload = new byte[21];
        payload[0] = 0x05;
        System.arraycopy(scriptHash, 0, payload, 1, 20);
        return base58check(payload);
    }

    private static String p2wpkhAddress(byte[] compressedPub) throws Exception {
        byte[] pubHash = ripemd160(sha256(compressedPub));
        int[] data = convertBits(pubHash, 8, 5, true);
        int[] withVersion = new int[data.length + 1];
        withVersion[0] = 0;
        System.arraycopy(data, 0, withVersion, 1, data.length);
        return bech32Encode("bc", withVersion);
    }

    // --------------------- base58check ---------------------

    private static String base58check(byte[] payload) throws Exception {
        byte[] h1 = sha256(payload);
        byte[] h2 = sha256(h1);
        byte[] data = new byte[payload.length + 4];
        System.arraycopy(payload, 0, data, 0, payload.length);
        System.arraycopy(h2, 0, data, payload.length, 4);
        return base58Encode(data);
    }

    private static String base58Encode(byte[] data) {
        BigInteger value = new BigInteger(1, data);
        StringBuilder sb = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = value.divideAndRemainder(base);
            sb.append(BASE58[divmod[1].intValue()]);
            value = divmod[0];
        }
        for (int i = 0; i < data.length && data[i] == 0; i++) {
            sb.append(BASE58[0]);
        }
        return sb.reverse().toString();
    }

    // --------------------- hash ---------------------

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] ripemd160(byte[] data) {
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(data, 0, data.length);
        byte[] out = new byte[20];
        digest.doFinal(out, 0);
        return out;
    }

    // --------------------- bech32 ---------------------

    private static String bech32Encode(String hrp, int[] data) {
        int[] checksum = bech32CreateChecksum(hrp, data);
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (int d : data) sb.append(BECH32_CHARSET.charAt(d));
        for (int c : checksum) sb.append(BECH32_CHARSET.charAt(c));
        return sb.toString();
    }

    private static int[] bech32CreateChecksum(String hrp, int[] data) {
        int[] values = concatInt(bech32HrpExpand(hrp), data);
        values = concatInt(values, new int[]{0, 0, 0, 0, 0, 0});
        int polymod = bech32Polymod(values) ^ 1;
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (polymod >> (5 * (5 - i))) & 31;
        }
        return out;
    }

    private static int[] bech32HrpExpand(String hrp) {
        int[] out = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            out[i] = hrp.charAt(i) >> 5;
            out[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
        }
        return out;
    }

    private static int bech32Polymod(int[] values) {
        int chk = 1;
        for (int v : values) {
            int b = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((b >> i) & 1) != 0) chk ^= BECH32_GEN[i];
            }
        }
        return chk;
    }

    private static int[] concatInt(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0, bits = 0;
        int maxv = (1 << toBits) - 1;
        List<Integer> ret = new ArrayList<>();
        for (byte b : data) {
            int value = b & 0xff;
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                ret.add((acc >> bits) & maxv);
            }
        }
        if (pad && bits > 0) {
            ret.add((acc << (toBits - bits)) & maxv);
        }
        int[] out = new int[ret.size()];
        for (int i = 0; i < out.length; i++) out[i] = ret.get(i);
        return out;
    }
}
