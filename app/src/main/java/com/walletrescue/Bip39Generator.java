package com.walletrescue;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Local BIP39 English mnemonic generation. No network access is used. */
public final class Bip39Generator {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private Bip39Generator() {}

    public static String generate(Context context, int wordCount) throws Exception {
        if (wordCount != 12 && wordCount != 24) {
            throw new IllegalArgumentException("فقط عبارت ۱۲ یا ۲۴ کلمه‌ای پشتیبانی می‌شود.");
        }
        List<String> words = loadWords(context);
        int entropyBytes = wordCount == 12 ? 16 : 32;
        byte[] entropy = new byte[entropyBytes];
        SECURE_RANDOM.nextBytes(entropy);

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(entropy);
        int checksumBits = entropyBytes / 4;
        StringBuilder bits = new StringBuilder(entropyBytes * 8 + checksumBits);
        for (byte value : entropy) appendByte(bits, value);
        for (int i = 0; i < checksumBits; i++) {
            bits.append((hash[0] >> (7 - i)) & 1);
        }

        StringBuilder phrase = new StringBuilder(wordCount * 9);
        for (int i = 0; i < wordCount; i++) {
            int index = Integer.parseInt(bits.substring(i * 11, i * 11 + 11), 2);
            if (i > 0) phrase.append(' ');
            phrase.append(words.get(index));
        }
        java.util.Arrays.fill(entropy, (byte) 0);
        return phrase.toString();
    }

    private static void appendByte(StringBuilder bits, byte value) {
        for (int bit = 7; bit >= 0; bit--) bits.append((value >> bit) & 1);
    }

    private static List<String> loadWords(Context context) throws Exception {
        List<String> words = new ArrayList<>(2048);
        try (InputStream in = context.getAssets().open("english.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) words.add(word);
            }
        }
        if (words.size() != 2048) {
            throw new IllegalStateException("فهرست استاندارد BIP39 ناقص است: " + words.size() + " کلمه");
        }
        return words;
    }
}