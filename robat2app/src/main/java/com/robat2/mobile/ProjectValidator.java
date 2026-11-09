package com.robat2.mobile;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.JSONTokener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline static project checks. This deliberately does not execute untrusted project code. */
public final class ProjectValidator {
    private ProjectValidator() {}

    public static String validate(File filesDir) {
        File root=new File(filesDir,"robat2-workspace");
        if(!root.isDirectory()) return "فضای پروژه خالی است؛ ابتدا فایل ZIP پروژه را وارد کن.";
        List<File> files=new ArrayList<>();
        collect(root,files);
        int checked=0, errors=0, warnings=0;
        StringBuilder report=new StringBuilder("گزارش بررسی ایستای پروژه\n");
        for(File file:files) {
            String name=file.getName().toLowerCase(Locale.ROOT);
            if(name.endsWith(".robat2-backup") || name.endsWith(".part")) continue;
            if(file.length()>2L*1024L*1024L) { warnings++; continue; }
            String content=read(file);
            if(content==null) { warnings++; continue; }
            String problem=null;
            try {
                if(name.endsWith(".json")) checkJson(content);
                else if(name.endsWith(".xml")) checkXml(content);
                else if(name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".js") ||
                        name.endsWith(".ts") || name.endsWith(".cpp") || name.endsWith(".c") ||
                        name.endsWith(".h") || name.endsWith(".cs")) checkBrackets(content);
            } catch(Exception e) {
                problem=e.getMessage()==null?"ساختار نامعتبر":e.getMessage();
            }
            if(name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".java") ||
                    name.endsWith(".kt") || name.endsWith(".js") || name.endsWith(".ts") ||
                    name.endsWith(".cpp") || name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".cs")) checked++;
            if(problem!=null) {
                errors++;
                if(errors<=30) report.append("\n✖ ").append(root.toURI().relativize(file.toURI()).getPath()).append(": ").append(problem);
            }
        }
        File gradle=new File(root,"build.gradle");
        File settings=new File(root,"settings.gradle");
        File appGradle=new File(root,"app/build.gradle");
        if(!gradle.exists()&&!settings.exists()&&!appGradle.exists()) {
            warnings++;
            report.append("\n⚠ فایل‌های اصلی Gradle پیدا نشدند؛ شاید ZIP فقط بخشی از پروژه باشد.");
        }
        report.insert(0,"فایل‌های پروژه: "+files.size()+" | فایل‌های بررسی‌شده: "+checked+
                " | خطاهای ساختاری: "+errors+" | هشدارها: "+warnings+"\n");
        if(errors==0) report.append("\n✓ خطای ساختاری در فایل‌های بررسی‌شده پیدا نشد.");
        report.append("\n\nمحدودیت: این بررسی جایگزین کامپایل و اجرای تست واقعی نیست؛ هیچ کد پروژه‌ای اجرا نشده است.");
        return report.toString();
    }

    private static void checkJson(String value) throws Exception {
        String s=value.trim();
        if(s.startsWith("{")) new org.json.JSONObject(s);
        else if(s.startsWith("[")) new org.json.JSONArray(s);
        else throw new Exception("JSON باید با { یا [ شروع شود.");
    }
    private static void checkXml(String value) throws Exception {
        XmlPullParser parser=Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
        parser.setInput(new StringReader(value));
        while(parser.next()!=XmlPullParser.END_DOCUMENT) { }
    }
    private static void checkBrackets(String s) throws Exception {
        ArrayDeque<Character> stack=new ArrayDeque<>();
        int state=0;
        boolean escape=false;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i), next=i+1<s.length()?s.charAt(i+1):'\0';
            if(state==1) { if(c=='\n') state=0; continue; }
            if(state==2) { if(c=='*'&&next=='/') { state=0; i++; } continue; }
            if(state==3||state==4) {
                if(escape) escape=false;
                else if(c=='\\') escape=true;
                else if((state==3&&c=='"')||(state==4&&c=='\''))
                    state=0;
                continue;
            }
            if(c=='/'&&next=='/') { state=1; i++; continue; }
            if(c=='/'&&next=='*') { state=2; i++; continue; }
            if(c=='"') { state=3; continue; }
            if(c=='\'') { state=4; continue; }
            if(c=='('||c=='{'||c=='[') stack.push(c);
            else if(c==')'||c=='}'||c==']') {
                if(stack.isEmpty()) throw new Exception("براکت بسته اضافی در موقعیت "+i);
                char open=stack.pop();
                if(!((open=='('&&c==')')||(open=='{'&&c=='}')||(open=='['&&c==']')))
                    throw new Exception("براکت‌ها با هم تطابق ندارند در موقعیت "+i);
            }
        }
        if(!stack.isEmpty()) throw new Exception("یک یا چند براکت باز بسته نشده است.");
        if(state==2||state==3||state==4) throw new Exception("رشته یا توضیح ناقص است.");
    }
    private static String read(File file) {
        try(InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);
            byte[] bytes=out.toByteArray();
            for(byte x:bytes) if(x==0) return null;
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(Exception e) { return null; }
    }
    private static void collect(File dir,List<File> out) {
        File[] children=dir.listFiles();
        if(children==null||out.size()>=20000) return;
        for(File f:children) {
            if(f.isDirectory()) collect(f,out);
            else if(f.isFile()) out.add(f);
            if(out.size()>=20000) return;
        }
    }
}
