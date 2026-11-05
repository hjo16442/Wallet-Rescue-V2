package com.walletrescue;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.MnemonicUtils;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

public final class TronDerivationScanner {
    public interface Progress { void onProgress(int done, int total); }
    public interface CancelCheck { boolean isCancelled(); }

    private static final char[] BASE58 =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private TronDerivationScanner() {}

    /**
     * Offline-only check of common BIP44 TRON paths:
     * m/44'/195'/account'/change/index
     * account 0..10, change 0..1, index 0..100.
     * No network APIs, logging, persistence, or transaction signing are used.
     */
    public static String scan(String mnemonic, String target, Progress progress,
                              CancelCheck cancel) throws Exception {
        String normalizedTarget = target == null ? "" : target.trim();
        if (!normalizedTarget.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            throw new Exception("آدرس هدف TRON معتبر نیست.");
        }

        byte[] seed = MnemonicUtils.generateSeed(mnemonic, "");
        int total = 11 * 2 * 101;
        int done = 0;
        try {
            DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed);
            DeterministicKey purpose = HDKeyDerivation.deriveChildKey(
                    master, new ChildNumber(44, true));
            DeterministicKey coin = HDKeyDerivation.deriveChildKey(
                    purpose, new ChildNumber(195, true));

            for (int account = 0; account <= 10; account++) {
                if (cancel != null && cancel.isCancelled()) {
                    return "بررسی به درخواست کاربر متوقف شد.\nتعداد مسیرهای بررسی‌شده: " + done;
                }
                DeterministicKey accountKey = HDKeyDerivation.deriveChildKey(
                        coin, new ChildNumber(account, true));
                for (int change = 0; change <= 1; change++) {
                    DeterministicKey changeKey = HDKeyDerivation.deriveChildKey(
                            accountKey, new ChildNumber(change, false));
                    for (int index = 0; index <= 100; index++) {
                        if (cancel != null && cancel.isCancelled()) {
                            return "بررسی به درخواست کاربر متوقف شد.\nتعداد مسیرهای بررسی‌شده: " + done;
                        }
                        DeterministicKey addressKey = HDKeyDerivation.deriveChildKey(
                                changeKey, new ChildNumber(index, false));
                        String address = toTronAddress(addressKey.getPrivKey());
                        done++;
                        if (progress != null) progress.onProgress(done, total);
                        if (normalizedTarget.equals(address)) {
                            return "تطابق پیدا شد!\n\nآدرس: " + address
                                    + "\nمسیر مشتق‌سازی: m/44'/195'/" + account
                                    + "'/" + change + "/" + index
                                    + "\n\nاین تطابق نشان می‌دهد عبارت واردشده کلید این آدرس را مشتق می‌کند. "
                                    + "هنوز هیچ مجوزی تغییر نکرده و هیچ تراکنشی انجام نشده است.";
                        }
                    }
                }
            }
            return "در محدوده بررسی‌شده تطابق پیدا نشد.\n"
                    + "مسیرهای بررسی‌شده: m/44'/195'/account'/change/index\n"
                    + "account: 0 تا 10، change: 0 تا 1، index: 0 تا 100\n\n"
                    + "این نتیجه ثابت نمی‌کند که عبارت هرگز آدرس هدف را تولید نمی‌کند؛ "
                    + "فقط می‌گوید در این محدوده متداول تطابقی نبود.";
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }

    private static String toTronAddress(BigInteger privateKey) throws Exception {
        ECKeyPair pair = ECKeyPair.create(privateKey);
        byte[] publicKey = Numeric.toBytesPadded(pair.getPublicKey(), 64);
        byte[] hash = Hash.sha3(publicKey); // Keccak-256, as used by TRON
        byte[] payload = new byte[21];
        payload[0] = 0x41;
        System.arraycopy(hash, hash.length - 20, payload, 1, 20);

        byte[] first = MessageDigest.getInstance("SHA-256").digest(payload);
        byte[] second = MessageDigest.getInstance("SHA-256").digest(first);
        byte[] checked = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(second, 0, checked, payload.length, 4);
        return base58Encode(checked);
    }

    private static String base58Encode(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        StringBuilder out = new StringBuilder();
        BigInteger radix = BigInteger.valueOf(58);
        while (value.signum() > 0) {
            BigInteger[] div = value.divideAndRemainder(radix);
            out.append(BASE58[div[1].intValue()]);
            value = div[0];
        }
        for (byte b : input) {
            if (b == 0) out.append(BASE58[0]);
            else break;
        }
        return out.reverse().toString();
    }
}
