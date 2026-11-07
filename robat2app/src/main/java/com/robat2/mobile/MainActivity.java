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
    private Spinner level;
    private TextView status;
    private Button send, audit;
    private LinearLayout messages;
    private ScrollView mainScroll;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF101827);
        getWindow().setNavigationBarColor(0xFF101827);
        ScrollView scroll = new ScrollView(this);
        mainScroll = scroll;
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
        TextView levelLabel = new TextView(this);
        levelLabel.setText("سطح توانایی Robat2");
        levelLabel.setTextColor(0xFF26364F);
        levelLabel.setTextSize(14);
        levelLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams levelLabelParams = params();
        levelLabelParams.topMargin = dp(12);
        root.addView(levelLabel, levelLabelParams);
        level = new Spinner(this);
        String[] levels = {
                "سطح ۱ - ربات حرفه‌ای",
                "سطح ۲ - مهندس خودکار (پیشنهادی)",
                "سطح ۳ - دستیار توسعه مستقل"
        };
        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, levels);
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        level.setAdapter(levelAdapter);
        level.setSelection(1);
        root.addView(level, params());
        send = new Button(this); send.setText("اجرای درخواست در سطح انتخاب‌شده");
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

        TextView chatTitle = new TextView(this);
        chatTitle.setText("گفت‌وگو با Robat2");
        chatTitle.setTextSize(19); chatTitle.setTypeface(null, Typeface.BOLD);
        chatTitle.setTextColor(0xFF17243A);
        LinearLayout.LayoutParams ct=params(); ct.topMargin=dp(24); root.addView(chatTitle,ct);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp=params(); mp.topMargin=dp(8); root.addView(messages,mp);
        addMessage("سلام! درخواستت را بنویس. Robat2 آن را در پروژه بررسی و اجرا می‌کند و پاسخ GitHub را همین‌جا نشان می‌دهد.", false);

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
        send.setEnabled(false); audit.setEnabled(false);
        addMessage(auditMode ? "درخواست شما: بررسی کامل پروژه" : "درخواست شما: " + instruction, true);
        status.setText("در حال پیدا کردن آخرین PR باز مخزن...");
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
                JSONObject body=new JSONObject(); body.put("body", auditMode ? (instruction.isEmpty() ? "/audit" : "/audit "+instruction) : "/fix [LEVEL:"+(level.getSelectedItemPosition()+1)+"] "+instruction);
                byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream os=c.getOutputStream()){os.write(data);}
                int code=c.getResponseCode();
                String response=readResponse(c);
                String message;
                long requestCommentId = -1L;
                if (code == 201) {
                    try { requestCommentId = new JSONObject(response).getLong("id"); } catch (Exception ignored) { }
                    message=(auditMode ? "درخواست بررسی کامل" : "درخواست اصلاح کد")+" با موفقیت در PR #"+number+" ثبت شد. در حال دریافت پاسخ Robat2...";
                }
                else if(code==401||code==403) message="GitHub دسترسی را نپذیرفت (HTTP "+code+"). مجوز Issues: Write را بررسی کن.";
                else if(code==404) message="PR یا مخزن پیدا نشد؛ نام مخزن را بررسی کن.";
                else message="ارسال ناموفق بود (HTTP "+code+"): "+response.substring(0,Math.min(220,response.length()));
                final String out=message;
                runOnUiThread(()->status.setText(out));
                if (code == 201 && requestCommentId > 0) {
                    runOnUiThread(()->addMessage("درخواست در PR #" + number + " ثبت شد؛ منتظر اجرای هوش مصنوعی هستم...", false));
                    String agentReply = waitForAgentReply(encodedRepo, number, secret, requestCommentId);
                    if (agentReply == null) {
                        runOnUiThread(()->{
                            status.setText("زمان انتظار تمام شد. ممکن است اجرای GitHub Actions هنوز ادامه داشته باشد.");
                            addMessage("هنوز پاسخ نهایی نرسیده است. وضعیت اجرا را از صفحه Actions بررسی کن: https://github.com/" + encodedRepo + "/actions", false);
                        });
                    } else {
                        runOnUiThread(()->status.setText("پاسخ Robat2 دریافت شد."));
                    }
                }
            }catch(Exception ex){runOnUiThread(()->status.setText("خطای ارتباط: "+ex.getMessage()));}
            finally{if(c!=null)c.disconnect();runOnUiThread(()->{send.setEnabled(true);audit.setEnabled(true);});}
        });
    }
    private void addMessage(String text, boolean fromUser) {
        runOnUiThread(() -> {
            if (messages == null) return;
            TextView bubble = new TextView(this);
            bubble.setText(text);
            bubble.setTextSize(14);
            bubble.setTextColor(fromUser ? 0xFFFFFFFF : 0xFF1D2A3D);
            bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
            bubble.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
            bubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    fromUser ? 0xFF2457A6 : 0xFFE4EAF2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    fromUser ? (int)(getResources().getDisplayMetrics().widthPixels * 0.82f) : -1, -2);
            lp.topMargin = dp(7);
            lp.gravity = fromUser ? android.view.Gravity.END : android.view.Gravity.START;
            messages.addView(bubble, lp);
            if (mainScroll != null) mainScroll.post(() -> mainScroll.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    private String waitForAgentReply(String repository, int number, String secret, long requestId) {
        long lastSeenId = requestId;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                // Allow longer AI edits/builds to finish: poll quickly at first, then
                // reduce API traffic while waiting for the workflow's final PR comment.
                Thread.sleep(attempt < 12 ? 5000L : 15000L);
                if (attempt == 11) runOnUiThread(() ->
                        status.setText("اجرای Robat2 بیشتر طول کشیده؛ همچنان منتظر نتیجه نهایی هستم..."));
                if (attempt == 35) runOnUiThread(() ->
                        status.setText("هنوز منتظر پایان تحلیل، اصلاح و ساخت پروژه هستم..."));
                HttpURLConnection c = null;
                try {
                    String endpoint = "https://api.github.com/repos/" + repository
                            + "/issues/" + number + "/comments?per_page=100&sort=created&direction=desc";
                    c = openApi(endpoint, secret);
                    if (c.getResponseCode() != 200) continue;
                    JSONArray comments = new JSONArray(readResponse(c));
                    for (int i = 0; i < comments.length(); i++) {
                        JSONObject item = comments.getJSONObject(i);
                        long id = item.optLong("id", 0L);
                        if (id <= lastSeenId) continue;
                        String body = item.optString("body", "");
                        String login = item.optJSONObject("user") == null ? ""
                                : item.getJSONObject("user").optString("login", "");
                        if (!login.endsWith("[bot]") && !body.contains("Robat2")) continue;
                        lastSeenId = Math.max(lastSeenId, id);
                        addMessage("Robat2:\n" + body, false);
                        if (body.contains("## ✅ تغییرات درخواستی پیاده‌سازی شد")
                                || body.contains("## 🔎 گزارش بررسی کامل")
                                || body.contains("⚠️ **اجرای هوش مصنوعی Robat2 ناموفق بود")
                                || body.contains("تغییر قابل اعمالی تولید نشد")
                                || body.contains("⛔")) return body;
                    }
                } finally {
                    if (c != null) c.disconnect();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception ignored) {
                // A transient polling/network error must not discard the submitted request.
            }
        }
        return null;
    }

    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
