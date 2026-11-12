package com.walletrescue;

import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import java.util.Arrays;
import java.util.List;

/** Derives only the first standard BIP44 Bitcoin mainnet receive address. */
public final class Bip39AddressDeriver {
    private Bip39AddressDeriver() {}

    public static String firstMainnetReceiveAddress(String phrase) throws Exception {
        List<String> words = Arrays.asList(phrase.trim().split("\\s+"));
        byte[] seed = MnemonicCode.INSTANCE.toSeed(words, "");
        try {
            DeterministicKey key = HDKeyDerivation.createMasterPrivateKey(seed);
            key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(44, true));
            key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(0, true));
            key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(0, true));
            key = HDKeyDerivation.deriveChildKey(key, ChildNumber.ZERO);
            key = HDKeyDerivation.deriveChildKey(key, ChildNumber.ZERO);
            return LegacyAddress.fromKey(MainNetParams.get(), key).toString();
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }
}