package com.walletrescue;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class WalletCore {
    private WalletCore() {}

    public static final class WalletResult {
        public final List<String> words;
        public final String btc;
        public final String btcPath = "m/44'/0'/0'/0/0";

        WalletResult(List<String> w, String b) {
            words = Collections.unmodifiableList(new ArrayList<>(w));
            btc = b;
        }

        public String mnemonic() { return String.join(" ", words); }
    }

    public static WalletResult create(int wordCount) throws Exception {
        if (wordCount != 12 && wordCount != 24) throw new IllegalArgumentException("فقط ۱۲ یا ۲۴ کلمه مجاز است.");
        byte[] entropy = new byte[wordCount == 12 ? 16 : 32];
        new SecureRandom().nextBytes(entropy);
        byte[] seed = null;
        byte[] privateBytes = null;
        try {
            List<String> words = MnemonicCode.INSTANCE.toMnemonic(entropy);
            seed = MnemonicCode.toSeed(words, "");
            DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed);
            DeterministicKey key = master;
            int[] indexes = {44, 0, 0, 0, 0};
            boolean[] hardened = {true, true, true, false, false};
            for (int i = 0; i < indexes.length; i++) key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(indexes[i], hardened[i]));
            privateBytes = key.getPrivKeyBytes();
            NetworkParameters params = MainNetParams.get();
            String address = Address.fromKey(params, ECKey.fromPrivate(privateBytes, true), Script.ScriptType.P2PKH).toString();
            return new WalletResult(words, address);
        } finally {
            Arrays.fill(entropy, (byte) 0);
            if (seed != null) Arrays.fill(seed, (byte) 0);
            if (privateBytes != null) Arrays.fill(privateBytes, (byte) 0);
        }
    }
}
