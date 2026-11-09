package com.robat2.mobile;

import android.os.StatFs;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Persistent, private, bounded source-code library for local project retrieval. */
public final class ProjectLibrary {
    private static final long MAX_BYTES = 10L * 1024L * 1024L * 1024L;
    private static final long RESERVE_BYTES = 300L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_FILES = 20000;
    private static final String[] EXT = {".java",".kt",".xml",".json",".txt",".md",".gradle",".properties",
        ".py",".js",".ts",".cpp",".h",".c",".yml",".yaml",".html",".css",".sql",".sh",".toml",
        ".go",".rs",".swift",".dart",".php",".rb",".cs",".jsx",".tsx",".vue",".svelte"};

    private ProjectLibrary() {}

    public static String importZip(InputStream source, File filesDir) throws IOException {
        File root = new File(filesDir, "project-library");
        if (!root.exists() && !root.mkdirs()) throw new IOException("ساخت کتابخانه پروژه ناموفق بود.");
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        long existing = directoryBytes(root);
        int accepted = 0, skipped = 0;
        long added = 0;
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                String name = entry.getName();
                if (!allowed(name) || unsafe(name) || accepted >= MAX_FILES) {
                    skipped++; zip.closeEntry(); continue;
                }
                File target = new File(root, name).getCanonicalFile();
                if (!target.getPath().startsWith(canonicalRoot)) {
                    skipped++; zip.closeEntry(); continue;
                }
                File parent = target.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                    skipped++; zip.closeEntry(); continue;
                }
                File temp = new File(target.getPath() + ".part");
                long fileBytes = 0;
                boolean valid = true;
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        fileBytes += n;
                        long available = new StatFs(filesDir.getAbsolutePath()).getAvailableBytes();
                        if (fileBytes > MAX_FILE_BYTES || existing + added + fileBytes > MAX_BYTES ||
                                available < n + RESERVE_BYTES) { valid = false; break; }
                        out.write(buffer, 0, n);
                    }
                }
                if (valid && fileBytes > 0 && !containsNul(temp)) {
                    if (target.exists()) target.delete();
                    if (!temp.renameTo(target)) { temp.delete(); skipped++; }
                    else { accepted++; added += fileBytes; }
                } else { temp.delete(); skipped++; }
                zip.closeEntry();
                if (existing + added >= MAX_BYTES) break;
            }
        }
        return "کتابخانه پروژه: " + accepted + " فایل ذخیره شد، " + skipped +
                " فایل رد شد؛ فضای مصرف‌شده " + formatBytes(directoryBytes(root)) +
                " از سقف ۱۰ گیگابایت.";
    }

    public static String findRelevant(File filesDir, String query, int maxChars) {
        File root = new File(filesDir, "project-library");
        if (!root.isDirectory()) return "";
        String[] terms = query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+");
        List<File> files = new ArrayList<>();
        collect(root, files);
        List<Scored> ranked = new ArrayList<>();
        for (File file : files) {
            String rel = root.toURI().relativize(file.toURI()).getPath();
            String lower = rel.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) if (term.length() > 1 && lower.contains(term)) score += 5;
            if (score > 0) ranked.add(new Scored(file, score));
        }
        ranked.sort((a,b) -> Integer.compare(b.score, a.score));
        StringBuilder result = new StringBuilder();
        int used = 0, chosen = 0;
        for (Scored item : ranked) {
            if (chosen >= 12 || result.length() >= maxChars) break;
            int remaining = maxChars - result.length();
            String content = readText(item.file, Math.min(12000, remaining));
            if (content.isEmpty()) continue;
            String rel = root.toURI().relativize(item.file.toURI()).getPath();
            result.append("\n\n===== LIBRARY FILE: ").append(rel).append(" =====\n").append(content);
            chosen++;
        }
        return result.toString();
    }

    public static String stats(File filesDir) {
        File root = new File(filesDir, "project-library");
        return directoryCount(root) + " فایل | " + formatBytes(directoryBytes(root)) + " / ۱۰ گیگابایت";
    }

    private static boolean allowed(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : EXT) if (lower.endsWith(ext)) return true;
        return false;
    }
    private static boolean unsafe(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\\\") ||
                name.matches("^[A-Za-z]:.*")) return true;
        for (String part : name.replace('\\\\','/').split("/"))
            if (part.equals("..") || part.equals(".")) return true;
        return false;
    }
    private static boolean containsNul(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1)
                for (int i=0;i<n;i++) if (b[i] == 0) return true;
        }
        return false;
    }
    private static String readText(File file, int limit) {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while (out.size() < limit && (n = in.read(b)) != -1)
                out.write(b, 0, Math.min(n, limit - out.size()));
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null || out.size() >= MAX_FILES) return;
        for (File f : children) {
            if (f.isDirectory()) collect(f, out);
            else if (f.isFile() && allowed(f.getName()) && !f.getName().endsWith(".part")) out.add(f);
            if (out.size() >= MAX_FILES) return;
        }
    }
    private static long directoryBytes(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total=0; File[] children=f.listFiles();
        if (children!=null) for (File child:children) total += directoryBytes(child);
        return total;
    }
    private static int directoryCount(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return allowed(f.getName()) ? 1 : 0;
        int total=0; File[] children=f.listFiles();
        if (children!=null) for (File child:children) total += directoryCount(child);
        return total;
    }
    private static String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
    private static final class Scored {
        final File file; final int score;
        Scored(File f, int s) { file=f; score=s; }
    }
}
