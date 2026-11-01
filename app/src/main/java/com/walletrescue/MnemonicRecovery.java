
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

public class MnemonicRecovery {

    public static List<String> findCandidates(
            Context context, String phrase) throws Exception {

        String normalized = Normalizer.normalize(
            phrase.trim(), Normalizer.Form.NFKD
        ).toLowerCase(Locale.ROOT);

        String[] words = normalized.split("\\s+");

        if (words.length != 12 && words.length != 24) {
            throw new Exception("Enter 12 or 24 words.");
        }

        int missingPosition = -1;

        for (int i = 0; i < words.length; i++) {
            if (words[i].equals("?")) {
                if (missingPosition != -1) {
                    throw new Exception("Only one ? is allowed.");
                }
                missingPosition = i;
            }
        }

        if (missingPosition == -1) {
            throw new Exception("Use ? for the missing word.");
        }

        InputStream stream =
            context.getAssets().open("bip39_english.txt");

        MnemonicCode mnemonic = new MnemonicCode(stream, null);

        List<String> dictionary = mnemonic.getWordList();
        HashSet<String> validWords = new HashSet<>(dictionary);

        for (int i = 0; i < words.length; i++) {
            if (i != missingPosition &&
                !validWords.contains(words[i])) {
                throw new Exception("Unknown word at position " + (i + 1));
            }
        }

        List<String> candidates = new ArrayList<>();

        for (String candidate : dictionary) {
            words[missingPosition] = candidate;

            try {
                mnemonic.check(Arrays.asList(words.clone()));
                candidates.add(candidate);
            } catch (MnemonicException invalid) {
                // Candidate does not pass BIP39 checksum.
            }
        }

        words[missingPosition] = "?";

        return candidates;
    }
}
