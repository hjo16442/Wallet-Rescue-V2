package com.robat2.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Sandboxed project workspace. Generated source is written only below app-private filesDir. */
public final class WorkspaceManager {
    private static final long MAX_TOTAL = 200L * 1024L * 1024L;
    private static final long MAX_FILE = 5L * 1024L * 1024L;
    private static final String OPEN = "<ROB2_FILE path=\"";
    private static final String MID = "\">";
    private static final String CLOSE = "</ROB2_FILE>";
    private static final Set<String> EXT = new HashSet<>(Arrays.asList(
        ".java",".kt",".xml",".json",".txt",".md",".gradle",".properties",".py",".js",".ts",
        ".cpp",".h",".c",".yml",".yaml",".html",".css",".sql",".sh",".toml",".go",".rs",
        ".swift",".dart",".php",".rb",".cs",".jsx",".tsx",".vue",".svelte"
    ));
    private WorkspaceManager() {}

    public static String formatInstructions() {
        return "For every file you create or modify, output its complete contents in this exact format, " +
            "with no markdown fence around the markers:\n" +
            "<ROB2_FILE path=\"relative/path.ext\">\nFULL FILE CONTENT\n</ROB2_FILE>\n" +
            "You may put a short Persian explanation outside these blocks. Never invent that tests/builds ran. " +
            "Use relative paths only. Include complete files, not diffs. Keep existing features unless asked otherwise.";
    }

    public static String applyGenerated(String answer, File filesDir) {
        if (answer == null) return "پاسخی برای ساخت فایل وجود ندارد.";
        File root = new File(filesDir, "robat2-workspace");
        try {
            if (!root.exists() && !root.mkdirs()) return "ساخت فضای پروژه ناموفق بود.";
            String canonicalRoot = root.getCanonicalPath() + File.separator;
            int saved=0, rejected=0;
            long total=directoryBytes(root);
            int cursor=0;
            while (true) {
                int start=answer.indexOf(OPEN,cursor);
                if (start<0) break;
                int pathEnd=answer.indexOf(MID,start+OPEN.length());
                if (pathEnd<0) { rejected++; break; }
                String path=answer.substring(start+OPEN.length(),pathEnd).trim();
                int bodyStart=pathEnd+MID.length();
                int end=answer.indexOf(CLOSE,bodyStart);
                if (end<0) { rejected++; break; }
                cursor=end+CLOSE.length();
                if (!safePath(path)) { rejected++; continue; }
                String content=answer.substring(bodyStart,end);
                byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                if (bytes.length==0 || bytes.length>MAX_FILE) { rejected++; continue; }
                File target=new File(root,path).getCanonicalFile();
                if (!target.getPath().startsWith(canonicalRoot)) { rejected++; continue; }
                long old=target.isFile()?target.length():0;
                if (total-old+bytes.length>MAX_TOTAL) { rejected++; continue; }
                File parent=target.getParentFile();
                if (parent==null || (!parent.exists()&&!parent.mkdirs())) { rejected++; continue; }
                if (target.exists()) {
                    File backup=new File(target.getPath()+".robat2-backup");
                    copy(target,backup);
                }
                File temp=new File(target.getPath()+".part");
                try (FileOutputStream out=new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
                if (target.exists() && !target.delete()) { temp.delete(); rejected++; continue; }
                if (!temp.renameTo(target)) { temp.delete(); rejected++; continue; }
                total=total-old+bytes.length;
                saved++;
            }
            return saved+" فایل در فضای امن پروژه ذخیره شد؛ "+rejected+
                " فایل رد شد. مسیر داخلی: robat2-workspace. هیچ دستور یا کدی اجرا نشد.";
        } catch (Exception e) {
            return "ذخیره فایل‌های تولیدشده ناموفق بود: "+e.getMessage();
        }
    }

    public static String exportZip(File filesDir, OutputStream destination) throws IOException {
        File root=new File(filesDir,"robat2-workspace");
        if(!root.isDirectory()) return "فضای پروژه هنوز فایلی ندارد.";
        java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new BufferedOutputStream(destination));
        List<File> files=new ArrayList<>(); collect(root,files);
        byte[] buffer=new byte[16384];
        for(File file:files) {
            String name=root.toURI().relativize(file.toURI()).getPath();
            if(name.endsWith(".part") || name.endsWith(".robat2-backup")) continue;
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            try(InputStream in=new BufferedInputStream(new FileInputStream(file))) {
                int n; while((n=in.read(buffer))!=-1) zip.write(buffer,0,n);
            }
            zip.closeEntry();
        }
        zip.finish(); zip.flush();
        return files.size()+" فایل برای خروجی پروژه بسته‌بندی شد.";
    }

    public static String findRelevant(File filesDir, String query, int maxChars) {
        File root=new File(filesDir,"robat2-workspace");
        if(!root.isDirectory() || maxChars<=0) return "";
        List<File> files=new ArrayList<>(); collect(root,files);
        String[] terms=query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+");
        List<FileScore> ranked=new ArrayList<>();
        for(File file:files) {
            String name=root.toURI().relativize(file.toURI()).getPath().toLowerCase(Locale.ROOT);
            if(name.endsWith(".part") || name.endsWith(".robat2-backup")) continue;
            int score=0;
            for(String term:terms) if(term.length()>1 && name.contains(term)) score+=10;
            if(name.endsWith("mainactivity.java") || name.endsWith("build.gradle") || name.endsWith("androidmanifest.xml")) score+=2;
            ranked.add(new FileScore(file,score));
        }
        ranked.sort((a,b)->Integer.compare(b.score,a.score));
        StringBuilder out=new StringBuilder(); int selected=0;
        for(FileScore item:ranked) {
            if(selected>=10 || out.length()>=maxChars) break;
            int remaining=maxChars-out.length();
            String content=readText(item.file,Math.min(7000,remaining));
            if(content.isEmpty()) continue;
            String path=root.toURI().relativize(item.file.toURI()).getPath();
            out.append("\\n\\n===== EXISTING WORKSPACE FILE: ").append(path).append(" =====\\n").append(content);
            selected++;
        }
        return out.toString();
    }

    public static String stats(File filesDir) {
        File root=new File(filesDir,"robat2-workspace");
        return count(root)+" فایل ساخته‌شده | "+(directoryBytes(root)/1024)+" KB";
    }
    private static boolean safePath(String path) {
        if(path==null || path.isEmpty() || path.startsWith("/") || path.startsWith("\\\\") ||
            path.matches("^[A-Za-z]:.*")) return false;
        String normalized=path.replace('\\','/');
        for(String part:normalized.split("/")) if(part.equals("..") || part.equals(".") || part.isEmpty()) return false;
        String lower=path.toLowerCase(Locale.ROOT);
        for(String ext:EXT) if(lower.endsWith(ext)) return true;
        return false;
    }
    private static final class FileScore { final File file; final int score; FileScore(File f,int s){file=f;score=s;} }\n    private static void collect(File dir,List<File> out) {
        File[] children=dir.listFiles(); if(children==null) return;
        for(File f:children) { if(f.isDirectory()) collect(f,out); else if(f.isFile()) out.add(f); }
    }
    private static int count(File dir) { List<File> f=new ArrayList<>(); if(dir.exists()) collect(dir,f); int n=0; for(File x:f) if(!x.getName().endsWith(".part")&&!x.getName().endsWith(".robat2-backup")) n++; return n; }
    private static long directoryBytes(File f) { if(!f.exists())return 0; if(f.isFile())return f.length(); long n=0; File[] a=f.listFiles(); if(a!=null)for(File x:a)n+=directoryBytes(x); return n; }
    private static void copy(File src,File dst)throws IOException { try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dst)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);} }
}
