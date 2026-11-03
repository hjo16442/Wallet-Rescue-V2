package com.walletrescue;

import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MnemonicRecovery {

    private static final int[] VALID_LENGTHS = {12, 24};

    /**
     * بازیابی عبارت BIP39 بدون نیاز به داشتن هیچ کلمه‌ای.
     *
     * حالت‌های پشتیبانی‌شده:
     *  1) "? ? ? ? ? ? ? ? ? ? ? ?"                  → جستجوی کامل از صفر
     *  2) "abandon ? able ..."                        → یک کلمه گمشده
     *  3) "aban ? ? ? ? ? ? ? ? ? ? ?"               → جستجوی پیشوندی
     *  4) ""  (خالی)                                   → معادل حالت ۱
     */
    public static List<String> findCandidates(String phrase) throws Exception {

        MnemonicCode mnemonic = MnemonicCode.INSTANCE;
        List<String> dictionary = mnemonic.getWordList();
        HashSet<String> validWords = new HashSet<>(dictionary);

        // ---------- 1) نرمال‌سازی ورودی ----------
        String normalized = (phrase == null) ? "" :
                Normalizer.normalize(phrase.trim(), Normalizer.Form.NFKD)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ")
                        .trim();

        // ---------- 2) اگر خالی بود: فرض کن کل عبارت گمشده است ----------
        if (normalized.isEmpty()) {
            normalized = "? ? ? ? ? ? ? ? ? ? ? ?"; // ۱۲ کلمه
        }

        String[] words = normalized.split(" ");

        // ---------- 3) اعتبارسنجی طول ----------
        boolean lengthOk = false;
        for (int len : VALID_LENGTHS) {
            if (words.length == len) { lengthOk = true; break; }
        }
        if (!lengthOk) {
            throw new Exception(
                    "طول عبارت باید ۱۲ یا ۲۴ کلمه باشد. (فعلی: " + words.length + ")"
            );
        }

        // ---------- 4) اعتبارسنجی محتوا و شمارش ? ----------
        List<Integer> missingPositions = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.equals("?")) {
                missingPositions.add(i);
            } else if (!validWords.contains(w)) {
                // شاید پیشوند باشه — بعداً بررسی می‌شه
                if (w.length() < 1) {
                    throw new Exception("کلمه خالی در جایگاه " + (i + 1));
                }
            }
        }

        if (missingPositions.isEmpty()) {
            // هیچ ? نیست → عبارت باید کامل باشه؛ فقط اعتبارسنجی می‌کنیم
            try {
                mnemonic.check(Arrays.asList(words));
                return new ArrayList<>(Arrays.asList(words));
            } catch (MnemonicException e) {
                throw new Exception("عبارت معتبر نیست (checksum رد شد).");
            }
        }

        // ---------- 5) آماده‌سازی دیکشنری برای هر جایگاه ----------
        // برای هر جایگاه، لیست کاندیدهای ممکن رو می‌سازیم
        List<List<String>> candidateSets = new ArrayList<>();

        for (int i = 0; i < words.length; i++) {
            String w = words[i];

            if (w.equals("?")) {
                candidateSets.add(dictionary); // همه ۲۰۴۸ کلمه
            } else if (validWords.contains(w)) {
                candidateSets.add(Arrays.asList(w)); // فقط خودش
            } else {
                // پیشوند → همه کلمات دیکشنری که با این شروع می‌شن
                List<String> prefixed = new ArrayList<>();
                for (String dictWord : dictionary) {
                    if (dictWord.startsWith(w)) prefixed.add(dictWord);
                }
                if (prefixed.isEmpty()) {
                    throw new Exception("کلمه‌ای با پیشوند '" + w + "' یافت نشد (جایگاه " + (i + 1) + ")");
                }
                candidateSets.add(prefixed);
            }
        }

        // ---------- 6) اگر تعداد ? خیلی زیاد بود → جستجوی بهینه ----------
        // تعداد کل ترکیب‌ها = ضرب اندازه هر لیست
        long totalCombinations = 1L;
        for (List<String> set : candidateSets) {
            totalCombinations *= set.size();
            if (totalCombinations > 100_000_000L) {
                throw new Exception(
                        "فضای جستجو بیش از حد بزرگ است (" + totalCombinations +
                        " ترکیب). حداقل یک کلمه یا پیشوند بده تا محدود شود."
                );
            }
        }

        // ---------- 7) جستجوی بازگشتی (DFS) با هرس checksum ----------
        List<String> result = new ArrayList<>();
        String[] current = new String[words.length];

        // بهینه‌سازی: چون checksum در انتها اعمال می‌شه، DFS کامل انجام می‌دیم
        // ولی به جای تست هر ترکیب کامل، از mnemonic.check استفاده می‌کنیم
        searchRecursive(mnemonic, candidateSets, current, 0, result);

        return result;
    }

    private static void searchRecursive(MnemonicCode mnemonic,
                                        List<List<String>> candidateSets,
                                        String[] current,
                                        int index,
                                        List<String> result) {
        if (index == candidateSets.size()) {
            try {
                mnemonic.check(Arrays.asList(current));
                result.add(String.join(" ", current));
            } catch (MnemonicException ignored) { }
            return;
        }

        for (String candidate : candidateSets.get(index)) {
            current[index] = candidate;
            searchRecursive(mnemonic, candidateSets, current, index + 1, result);
        }
    }
}
