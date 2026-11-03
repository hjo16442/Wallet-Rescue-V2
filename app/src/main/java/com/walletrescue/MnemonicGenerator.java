
package com.walletrescue;

import org.bitcoinj.crypto.MnemonicCode;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

public final class MnemonicGenerator {

    private MnemonicGenerator() {}

    public static List<String> generate(int wordCount)
            throws Exception {

        int entropyLength;

        if (wordCount == 12) {
            entropyLength = 16;
        } else if (wordCount == 24) {
            entropyLength = 32;
        } else {
            throw new IllegalArgumentException(
                "Only 12 or 24 words are supported."
            );
        }

        byte[] entropy = new byte[entropyLength];

        new SecureRandom().nextBytes(entropy);

        try {
            List<String> words =
                MnemonicCode.INSTANCE.toMnemonic(entropy);

            MnemonicCode.INSTANCE.check(words);

            return words;

        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }
}
