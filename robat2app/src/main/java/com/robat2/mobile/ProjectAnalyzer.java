package com.robat2.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Engineering preflight: identifies project structure and risks without executing imported code. */
public final class ProjectAnalyzer {
    private ProjectAnalyzer() {}
    private static final int MAX_FILES=20000, MAX_SCAN_BYTES=1024*1024;

    public static String analyze(File filesDir) {
        File root=new File(filesDir,"robat2-workspace");
        if(!root.isDirectory()) return "فضای پروژه خالی است؛ ابتدا ZIP پروژه را وارد کن.";
        List<File> files=new ArrayList<>();
        collect(root,files);
        Map<String,Integer> langs=new TreeMap<>();
        List<String> buildFiles=new ArrayList<>(), testFiles=new ArrayList<>(), warnings=new ArrayList<>(), todos=new ArrayList<>();
        int scanned=0, secretHits=0, dependencyFiles=0, docs=0;
        Pattern secret=Pattern.compile("(?i)(api[_-]?key|secret|password|access[_-]?token|private[_-]?key)\\s*[:=]\\s*[\\\"'][^\\\"']{8,}[\\\"']");
        Pattern todo=Pattern.compile("(?i)\\b(TODO|FIXME|XXX)\\b");
        for(File file:files) {
            if(!file.isFile()) continue;
            String path=root.toURI().relativize(file.toURI()).getPath();
            String name=file.getName().toLowerCase(Locale.ROOT);
            if(name.endsWith(".robat2-backup")||name.endsWith(".part")) continue;
            String ext=extension(name);
            if(!ext.isEmpty()) langs.put(ext,langs.containsKey(ext)?langs.get(ext)+1:1);
            if(name.equals("build.gradle")||name.equals("build.gradle.kts")||name.equals("pom.xml")||
               name.equals("package.json")||name.equals("pyproject.toml")||name.equals("cargo.toml")||
               name.equals("cmakelists.txt")||name.equals("pubspec.yaml")||name.equals("go.mod"))
                buildFiles.add(path);
            if(path.toLowerCase(Locale.ROOT).contains("/test/")||path.toLowerCase(Locale.ROOT).contains("/tests/")||
               path.toLowerCase(Locale.ROOT).startsWith("test/")||path.toLowerCase(Locale.ROOT).startsWith("tests/")||
               name.endsWith("test.java")||name.endsWith("test.kt")||name.endsWith("_test.py")||
               name.endsWith(".spec.js")||name.endsWith(".test.js")) testFiles.add(path);
            if(name.equals("readme.md")||name.equals("readme.txt")||name.equals("contributing.md")) docs++;
            if(name.equals("requirements.txt")||name.equals("package-lock.json")||name.equals("yarn.lock")||
               name.equals("pnpm-lock.yaml")||name.equals("gradle.lockfile")||name.equals("poetry.lock")||
               name.equals("cargo.lock")||name.equals("go.sum")) dependencyFiles++;
            if(file.length()>MAX_SCAN_BYTES) { warnings.add("فایل بزرگ اسکن نشد: "+path); continue; }
            String text=read(file);
            if(text==null) continue;
            Matcher sm=secret.matcher(text);
            while(sm.find()) { secretHits++; if(secretHits<=10) warnings.add("احتمال اطلاعات محرمانه در "+path+"؛ مقدار را از پروژه حذف و کلید را تعویض کن."); }
            Matcher tm=todo.matcher(text);
            while(tm.find()) { if(todos.size()<8) todos.add(path+": "+tm.group(1)); }
            scanned++;
        }
        StringBuilder out=new StringBuilder("گزارش تحلیل مهندسی پروژه\n");
        out.append("فایل‌ها: ").append(files.size()).append(" | متن اسکن‌شده: ").append(scanned).append("\n");
        out.append("\nزبان‌ها و فایل‌ها:\n");
        if(langs.isEmpty()) out.append("- زبان قابل‌شناسایی پیدا نشد.\n");
        else for(Map.Entry<String,Integer> e:langs.entrySet()) out.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        out.append("\nسیستم‌های ساخت و مدیریت وابستگی:\n");
        if(buildFiles.isEmpty()) out.append("- فایل پیکربندی ساخت شناخته‌شده پیدا نشد.\n");
        else for(String p:buildFiles) out.append("- ").append(p).append("\n");
        out.append("فایل‌های قفل وابستگی: ").append(dependencyFiles).append(" | فایل‌های راهنما: ").append(docs).append("\n");
        out.append("\nآزمون‌ها: ").append(testFiles.size()).append(" مورد شناسایی شد.\n");
        for(int i=0;i<Math.min(12,testFiles.size());i++) out.append("- ").append(testFiles.get(i)).append("\n");
        if(testFiles.isEmpty()) out.append("- پوشه/فایل تست شناخته‌شده پیدا نشد؛ ابتدا تست واحد اضافه کن.\n");
        if(!todos.isEmpty()) {
            out.append("\nموارد TODO/FIXME:\n");
            for(String t:todos) out.append("- ").append(t).append("\n");
        }
        out.append("\nبررسی امنیتی: ").append(secretHits).append(" الگوی احتمالی کلید/رمز پیدا شد.\n");
        for(String w:warnings) out.append("⚠ ").append(w).append("\n");
        if(buildFiles.isEmpty()) out.append("\nپیشنهاد: نوع پروژه و ابزار ساخت آن را مشخص کن؛ فایل ساخت را حدس نزن.\n");
        else if(buildFiles.toString().contains("build.gradle")||buildFiles.toString().contains("pom.xml"))
            out.append("\nگام پیشنهادی CI: ابتدا بررسی پیکربندی، سپس تست واحد و در پایان build؛ اجرای دستورات فقط در محیط ایزوله و با اجازه صریح انجام شود.\n");
        out.append("\nاین گزارش تحلیل ایستاست؛ هیچ اسکریپت، کد، وابستگی یا دستور پروژه اجرا نشده است.");
        return out.toString();
    }

    private static String extension(String name) {
        int i=name.lastIndexOf('.');
        return i<0?"(بدون پسوند)":name.substring(i);
    }
    private static String read(File file) {
        try(InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n,total=0;
            while((n=in.read(b))!=-1) { total+=n; if(total>MAX_SCAN_BYTES) return null; out.write(b,0,n); }
            byte[] bytes=out.toByteArray();
            for(byte x:bytes) if(x==0) return null;
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(Exception e) { return null; }
    }
    private static void collect(File dir,List<File> out) {
        File[] children=dir.listFiles();
        if(children==null||out.size()>=MAX_FILES) return;
        for(File f:children) {
            if(f.isDirectory()) collect(f,out);
            else if(f.isFile()) out.add(f);
            if(out.size()>=MAX_FILES) return;
        }
    }
}
