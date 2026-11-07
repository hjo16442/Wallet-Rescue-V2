package com.robat2.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText repo, token, request;
    private TextView status;
    private Button send, audit;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF101827);
        getWindow().setNavigationBarColor(0xFF101827);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(28),dp(22),dp(24));
        root.setBackgroundColor(0xFFF4F6FA);
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("Robat2");
        title.setTextSize(30);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF17243A);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("دستیار هوشمند اصلاح کد در GitHub");
        sub.setTextSize(15); sub.setTextColor(0xFF53627A);
        LinearLayout.LayoutParams sp = params(); sp.bottomMargin=dp(22); root.addView(sub,sp);

        repo = field(root,"نام مخزن (owner/repository)","hjo16442/Wallet-Rescue-V2",false);
        token = field(root,"توکن GitHub (Fine-grained PAT)","توکن را اینجا وارد کنید",true);
        request = field(root,"درخواست تغییر کد","مثلاً: فقط متن دکمه اصلی را فارسی کن",false);
        request.setMinLines(4); request.setGravity(48);
        send = new Button(this); send.setText("اجرای درخواست و اصلاح کد");
        LinearLayout.LayoutParams bp=params(); bp.topMargin=dp(10); root.addView(send,bp);
        audit = new Button(this); audit.setText("بررسی کامل پروژه و گزارش ایرادها");
        LinearLayout.LayoutParams ap=params(); ap.topMargin=dp(6); root.addView(audit,ap);
        status = new TextView(this); status.setText("شماره PR به‌صورت خودکار از آخرین PR باز مخزن انتخاب می‌شود.");
        status.setTextSize(14); status.setTextColor(0xFF45546B);
        LinearLayout.LayoutParams st=params(); st.topMargin=dp(14); root.addView(status,st);
        TextView note=new TextView(this);
        note.setText("امنیت: توکن فقط هنگام ارسال در حافظه برنامه استفاده می‌شود و در مخزن یا پیام درخواست ذخیره نمی‌شود. آن را برای کسی نفرستید.");
        note.setTextSize(12); note.setTextColor(0xFF68758A);
        LinearLayout.LayoutParams nt=params(); nt.topMargin=dp(20); root.addView(note,nt);
        setContentView(scroll);
        send.setOnClickListener(v -> submit(false));
        audit.setOnClickListener(v -> submit(true));
    }

    private EditText field(LinearLayout parent,String label,String hint,boolean secret) {
        TextView tv=new TextView(this); tv.setText(label); tv.setTextColor(0xFF26364F);
        tv.setTextSize(14); tv.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams lp=params(); lp.topMargin=dp(12); parent.addView(tv,lp);
        EditText e=new EditText(this); e.setSingleLine(!label.equals("درخواست تغییر کد"));
        e.setTextSize(15); e.setHint(hint); e.setPadding(dp(12),dp(10),dp(12),dp(10));
        if(secret) e.setInputType(129);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6B7D99));
        LinearLayout.LayoutParams ep=params(); ep.topMargin=dp(4); parent.addView(e,ep);
        return e;
    }
    private LinearLayout.LayoutParams params(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    private String readResponse(HttpURLConnection c) throws Exception {
        InputStream is = c.getResponseCode() >= 200 && c.getResponseCode() < 300
                ? c.getInputStream() : c.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (is != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) response.append(line);
        }
        return response.toString();
    }

    private HttpURLConnection openApi(String url, String secret) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        c.setRequestProperty("Authorization","Bearer "+secret);
        return c;
    }

    private void submit(boolean auditMode){
        final String repository=repo.getText().toString().trim();
        final String secret=token.getText().toString().trim();
        final String instruction=request.getText().toString().trim();
        if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){status.setText("نام مخزن را دقیقاً به شکل owner/repository وارد کنید؛ مثال: hjo16442/Wallet-Rescue-V2");return;}
        if(secret.isEmpty()||(!auditMode && instruction.isEmpty())){status.setText("توکن را وارد کنید؛ برای اجرای درخواست، متن درخواست هم لازم است.");return;}
        send.setEnabled(false); status.setText("در حال پیدا کردن آخرین PR باز مخزن...");
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(request.getWindowToken(),0);
        worker.execute(()->{
            HttpURLConnection c=null;
            try{
                String encodedRepo = repository;
                c=openApi("https://api.github.com/repos/"+encodedRepo+"/pulls?state=open&sort=updated&direction=desc&per_page=1",secret);
                int listCode=c.getResponseCode();
                String listResponse=readResponse(c);
                c.disconnect(); c=null;
                if(listCode!=200){
                    final String msg = listCode==401||listCode==403
                        ? "GitHub دسترسی را نپذیرفت (HTTP "+listCode+"). مجوز Pull requests: Read و Issues: Write را برای توکن فعال کن."
                        : listCode==404 ? "مخزن پیدا نشد. نام را به شکل owner/repository بررسی کن."
                        : "دریافت PRها ناموفق بود (HTTP "+listCode+").";
                    runOnUiThread(()->status.setText(msg)); return;
                }
                JSONArray prs=new JSONArray(listResponse);
                if(prs.length()==0){runOnUiThread(()->status.setText("هیچ PR بازی در این مخزن نیست. ابتدا یک Pull Request باز کن."));return;}
                int number=prs.getJSONObject(0).getInt("number");
                c=openApi("https://api.github.com/repos/"+encodedRepo+"/issues/"+number+"/comments",secret);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json; charset=utf-8");
                JSONObject body=new JSONObject(); body.put("body", auditMode ? (instruction.isEmpty() ? "/audit" : "/audit "+instruction) : "/fix "+instruction);
                byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream os=c.getOutputStream()){os.write(data);}
                int code=c.getResponseCode();
                String response=readResponse(c);
                String message;
                if(code==201) message=(auditMode ? "درخواست بررسی کامل" : "درخواست اصلاح کد")+" با موفقیت در PR #"+number+" ثبت شد. اجرای Robat2 به فعال بودن GitHub Actions وابسته است.";
                else if(code==401||code==403) message="GitHub دسترسی را نپذیرفت (HTTP "+code+"). مجوز Issues: Write را بررسی کن.";
                else if(code==404) message="PR یا مخزن پیدا نشد؛ نام مخزن را بررسی کن.";
                else message="ارسال ناموفق بود (HTTP "+code+"): "+response.substring(0,Math.min(220,response.length()));
                final String out=message;
                runOnUiThread(()->status.setText(out));
            }catch(Exception ex){runOnUiThread(()->status.setText("خطای ارتباط: "+ex.getMessage()));}
            finally{if(c!=null)c.disconnect();runOnUiThread(()->send.setEnabled(true));}
        });
    }
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
