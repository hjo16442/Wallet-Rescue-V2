package com.walletrescue;

import android.content.Context;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SafeMnemonicRecovery {

    private static final String WORDLIST_SHA256 =
        "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db";

    private SafeMnemonicRecovery() {}

    public static boolean isValidCompletePhrase(Context context, String phrase) {
        if (context == null || phrase == null) return false;
        try {
            String normalized = Normalizer.normalize(
                phrase.trim(), Normalizer.Form.NFKD
            ).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            String[] words = normalized.split(" ");
            if (words.length != 12 && words.length != 24) return false;
            try (InputStream input = context.getAssets().open("bip39_english.txt")) {
                MnemonicCode mnemonic = new MnemonicCode(input, WORDLIST_SHA256);
                mnemonic.check(Arrays.asList(words));
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static List<String> findCandidates(
        Context context, String phrase
    ) throws Exception {

        if (phrase == null || phrase.trim().isEmpty()) {
            throw new Exception("عبارت بازیابی را وارد کن.");
        }

        String normalized = Normalizer.normalize(
            phrase.trim(), Normalizer.Form.NFKD
        ).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        String[] words = normalized.split(" ");

        if (words.length != 12 && words.length != 24) {
            throw new Exception("عبارت باید دقیقاً ۱۲ یا ۲۴ کلمه داشته باشد.");
        }

        int missing = -1;
        int missingCount = 0;

        for (int i = 0; i < words.length; i++) {
            if (words[i].equals("?")) {
                missing = i;
                missingCount++;
            }
        }

        if (missingCount != 1) {
            throw new Exception("باید دقیقاً یک کلمه را با علامت ? مشخص کنی.");
        }

        try (InputStream input =
                 context.getAssets().open("bip39_english.txt")) {

            MnemonicCode mnemonic =
                new MnemonicCode(input, WORDLIST_SHA256);

            List<String> dictionary = mnemonic.getWordList();
            Set<String> validWords = new HashSet<>(dictionary);

            for (int i = 0; i < words.length; i++) {
                if (i != missing && !validWords.contains(words[i])) {
                    throw new Exception(
                        "کلمه شماره " + (i + 1) +
                        " در فهرست استاندارد BIP39 نیست."
                    );
                }
            }

            List<String> candidates = new ArrayList<>();

            for (String candidate : dictionary) {
                words[missing] = candidate;

                try {
                    mnemonic.check(Arrays.asList(words.clone()));
                    candidates.add(String.join(" ", words));
                } catch (MnemonicException ignored) {
                    // این کلمه با checksum سازگار نیست.
                }
            }

            words[missing] = "?";
            return candidates;
        }
    }
}
