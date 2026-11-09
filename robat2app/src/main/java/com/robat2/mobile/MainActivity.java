package com.robat2.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "robat2_settings";
    private static final String HISTORY_KEY = "conversation_history";
    private static final long MIN_MODEL_BYTES = 1500000000L;
    private static final int MAX_HISTORY_CHARS = 10000;
    private EditText input;
    private TextView status, conversation;
    private Button send, clear;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File modelFile;
    private final StringBuilder history = new StringBuilder();
    private volatile boolean modelReady = false;
    private volatile boolean generating = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF101827);
        getWindow().setNavigationBarColor(0xFF101827);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        history.append(prefs.getString(HISTORY_KEY, ""));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(18),dp(16),dp(12));
        root.setBackgroundColor(0xFFF4F6FA);

        TextView title = new TextView(this);
        title.setText("Robat2 — دستیار مهندسی نرم‌افزار آفلاین");
        title.setTextSize(20); title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF17243A); root.addView(title);

        status = new TextView(this);
        status.setText("در حال آماده‌سازی مدل محلی...");
        status.setTextSize(13); status.setTextColor(0xFF45546B);
        LinearLayout.LayoutParams st = params(); st.topMargin=dp(8); root.addView(status,st);

        ScrollView scroll = new ScrollView(this);
        conversation = new TextView(this);
        conversation.setText(history.length()==0
                ? "سلام! من Robat2 هستم. درخواست برنامه‌نویسی‌ات را بنویس. پردازش مدل روی همین گوشی انجام می‌شود.\n"
                : renderHistory(history.toString()));
        conversation.setTextSize(15); conversation.setTextColor(0xFF1D2A3D);
        conversation.setTextIsSelectable(true);
        conversation.setPadding(dp(12),dp(12),dp(12),dp(12));
        scroll.addView(conversation);
        LinearLayout.LayoutParams cp=params(); cp.weight=1; cp.height=0; cp.topMargin=dp(12); root.addView(scroll,cp);

        input = new EditText(this);
        input.setHint("درخواستت را فارسی بنویس...");
        input.setMinLines(3); input.setMaxLines(7); input.setGravity(Gravity.TOP);
        root.addView(input,params());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        send = new Button(this); send.setText("ارسال درخواست"); send.setEnabled(false);
        clear = new Button(this); clear.setText("گفتگوی جدید");
        actions.addView(send,new LinearLayout.LayoutParams(0,-2,2));
        actions.addView(clear,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams ap=params(); ap.topMargin=dp(8); root.addView(actions,ap);
        setContentView(root);
        send.setOnClickListener(v -> submit());
        clear.setOnClickListener(v -> clearConversation());
        prepareModel();
    }

    private void prepareModel() {
        worker.execute(() -> {
            try {
                modelFile = new File(getFilesDir(), "robat2-model.gguf");
                if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
                    runOnUiThread(() -> status.setText("در حال کپی مدل ۱٫۷ گیگابایتی؛ برنامه را نبند..."));
                    File temp = new File(getFilesDir(), "robat2-model.gguf.part");
                    try (InputStream in = getAssets().open("robat2-model.gguf");
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                        byte[] buffer = new byte[1024 * 1024]; int n;
                        while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                    }
                    if (temp.length() < MIN_MODEL_BYTES) {
                        temp.delete();
                        throw new IOException("فایل مدل ناقص است.");
                    }
                    if (modelFile.exists()) modelFile.delete();
                    if (!temp.renameTo(modelFile)) {
                        temp.delete();
                        throw new IOException("ذخیره مدل در حافظه داخلی ناموفق بود.");
                    }
                }
                runOnUiThread(() -> status.setText("مدل کپی شد؛ در حال بارگذاری روی گوشی..."));
                boolean ok = LocalModelEngine.loadModel(modelFile.getAbsolutePath());
                modelReady = ok;
                runOnUiThread(() -> {
                    status.setText(ok
                            ? "آماده است — پردازش کاملاً آفلاین"
                            : "بارگذاری مدل ناموفق بود؛ فضای خالی و RAM را بررسی کن.");
                    send.setEnabled(ok);
                });
            } catch (Throwable e) {
                modelReady = false;
                runOnUiThread(() -> status.setText("آماده‌سازی ناموفق: " + safeMessage(e)));
            }
        });
    }

    private void submit() {
        final String question=input.getText().toString().trim();
        if(question.isEmpty() || generating) return;
        if(!modelReady) { status.setText("مدل هنوز آماده نیست."); return; }
        if(question.length()>6000) { status.setText("درخواست خیلی طولانی است؛ آن را به چند بخش تقسیم کن."); return; }
        generating = true;
        send.setEnabled(false);
        append("\n\nشما: "+question+"\n\nRobat2: در حال پردازش روی گوشی...");
        input.setText("");
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(),0);
        worker.execute(() -> {
            String system = "You are Robat2, a senior but careful offline software engineering assistant. Answer in Persian unless code is requested. You can explain architecture, inspect code pasted by the user, design implementation plans, write complete files and patches, debug Java, Kotlin, Android, Python, JavaScript, C++, SQL and Gradle. For code changes, state exact file paths and provide complete, buildable code or a minimal patch. Never claim you accessed or changed files, ran tests, built an APK, or modified GitHub unless a tool actually did so. Ask for missing source files and error logs instead of guessing. Preserve existing features. Explain risks and provide a test plan. Protect secrets; never ask for wallet seed phrases, private keys, passwords or OTPs.";
            String prompt = "<|im_start|>system\n"+system+"<|im_end|>\n"
                    + history.toString()
                    + "<|im_start|>user\n"+question+"<|im_end|>\n<|im_start|>assistant\n";
            String answer;
            try { answer=LocalModelEngine.generate(prompt,700); }
            catch(Throwable e) { answer="خطای اجرای مدل: "+safeMessage(e); }
            if(answer==null || answer.trim().isEmpty()) answer="مدل پاسخ متنی تولید نکرد. درخواست را کوتاه‌تر یا دقیق‌تر بنویس.";
            synchronized(history) {
                history.append("<|im_start|>user\n").append(question).append("<|im_end|>\n")
                        .append("<|im_start|>assistant\n").append(answer).append("<|im_end|>\n");
                trimHistory();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HISTORY_KEY,history.toString()).apply();
            }
            final String result=answer;
            runOnUiThread(() -> {
                append("\nRobat2: "+result);
                status.setText("پاسخ با مدل محلی تولید شد؛ اینترنت استفاده نشد.");
                generating = false;
                send.setEnabled(modelReady);
            });
        });
    }

    private void trimHistory() {
        if(history.length()>MAX_HISTORY_CHARS) {
            int cut=history.length()-MAX_HISTORY_CHARS;
            int boundary=history.indexOf("<|im_start|>user",cut);
            if(boundary>=0) history.delete(0,boundary);
            else history.delete(0,cut);
        }
    }

    private void clearConversation() {
        if(generating) { status.setText("تا پایان پاسخ فعلی نمی‌توان گفتگو را پاک کرد."); return; }
        history.setLength(0);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(HISTORY_KEY).apply();
        conversation.setText("گفتگوی جدید شروع شد. درخواستت را بنویس.");
        status.setText(modelReady ? "مدل آفلاین آماده است." : "مدل هنوز آماده نیست.");
    }

    private String renderHistory(String raw) {
        String value=raw.replace("<|im_start|>user\n","\nشما: ")
                .replace("<|im_start|>assistant\n","\nRobat2: ")
                .replace("<|im_end|>","");
        return value;
    }
    private String safeMessage(Throwable e) {
        String m=e.getMessage(); return m==null ? e.getClass().getSimpleName() : m;
    }
    private void append(String value) { conversation.append(value); }
    private LinearLayout.LayoutParams params(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onDestroy() {
        modelReady=false;
        worker.execute(LocalModelEngine::release);
        worker.shutdown();
        super.onDestroy();
    }
}
