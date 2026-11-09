package com.robat2.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText input;
    private TextView status, conversation;
    private Button send;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File modelFile;
    private final StringBuilder history = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF101827);
        getWindow().setNavigationBarColor(0xFF101827);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(20),dp(16),dp(12));
        root.setBackgroundColor(0xFFF4F6FA);

        TextView title = new TextView(this);
        title.setText("Robat2 — دستیار برنامه‌نویسی آفلاین");
        title.setTextSize(22); title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF17243A); root.addView(title);

        status = new TextView(this);
        status.setText("در حال آماده‌سازی مدل محلی...");
        status.setTextSize(13); status.setTextColor(0xFF45546B);
        LinearLayout.LayoutParams st = params(); st.topMargin=dp(8); root.addView(status,st);

        ScrollView scroll = new ScrollView(this);
        conversation = new TextView(this);
        conversation.setText("سلام! من Robat2 هستم. درخواست برنامه‌نویسی‌ات را بنویس. پردازش روی همین گوشی انجام می‌شود.\n");
        conversation.setTextSize(15); conversation.setTextColor(0xFF1D2A3D);
        conversation.setPadding(dp(12),dp(12),dp(12),dp(12));
        scroll.addView(conversation);
        LinearLayout.LayoutParams cp=params(); cp.weight=1; cp.height=0; cp.topMargin=dp(12); root.addView(scroll,cp);

        input = new EditText(this);
        input.setHint("درخواستت را فارسی بنویس...");
        input.setMinLines(3); input.setGravity(Gravity.TOP);
        root.addView(input,params());

        send = new Button(this); send.setText("ارسال درخواست");
        LinearLayout.LayoutParams bp=params(); bp.topMargin=dp(8); root.addView(send,bp);
        setContentView(root);
        send.setOnClickListener(v -> submit());
        prepareModel();
    }

    private void prepareModel() {
        worker.execute(() -> {
            try {
                modelFile = new File(getFilesDir(), "robat2-model.gguf");
                if (!modelFile.exists() || modelFile.length() < 1000000000L) {
                    runOnUiThread(() -> status.setText("در حال آماده‌سازی فایل مدل؛ این مرحله ممکن است چند دقیقه طول بکشد..."));
                    try (InputStream in = getAssets().open("robat2-model.gguf");
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(modelFile))) {
                        byte[] buffer = new byte[1024 * 1024]; int n;
                        while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                    }
                }
                runOnUiThread(() -> status.setText("در حال بارگذاری مدل روی گوشی..."));
                boolean ok = LocalModelEngine.loadModel(modelFile.getAbsolutePath());
                runOnUiThread(() -> status.setText(ok
                        ? "مدل آفلاین آماده است. اینترنت لازم نیست."
                        : "بارگذاری مدل ناموفق بود. فضای خالی و سازگاری حافظه را بررسی کن."));
            } catch (Throwable e) {
                runOnUiThread(() -> status.setText("آماده‌سازی مدل ناموفق بود: " + e.getMessage()));
            }
        });
    }

    private void submit() {
        String question=input.getText().toString().trim();
        if(question.isEmpty()) return;
        if(modelFile==null || !modelFile.exists()) {
            status.setText("مدل هنوز آماده نیست."); return;
        }
        send.setEnabled(false);
        append("\n\nشما: "+question+"\n\nRobat2: در حال فکر کردن...");
        input.setText("");
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(),0);
        worker.execute(() -> {
            String system = "You are Robat2, a careful offline software engineering assistant. Answer in Persian unless code is requested. Help with Java, Kotlin, Android, Python, JavaScript, C++, SQL, debugging and project architecture. Never claim you changed files on the device unless a file operation actually happened. Ask for missing project files when needed. Protect secrets and never request wallet recovery phrases or private keys.";
            String prompt = "<|im_start|>system\n"+system+"<|im_end|>\n"
                    + history.toString()
                    + "<|im_start|>user\n"+question+"<|im_end|>\n<|im_start|>assistant\n";
            String answer;
            try { answer=LocalModelEngine.generate(prompt,900); }
            catch(Throwable e) { answer="خطای اجرای مدل: "+e.getMessage(); }
            final String result=answer;
            history.append("<|im_start|>user\n").append(question).append("<|im_end|>\n")
                    .append("<|im_start|>assistant\n").append(result).append("<|im_end|>\n");
            if(history.length()>18000) history.delete(0, history.length()-14000);
            runOnUiThread(() -> {
                int idx=conversation.getText().toString().lastIndexOf("Robat2: در حال فکر کردن...");
                if(idx>=0) conversation.setText(conversation.getText().toString().substring(0,idx)+"Robat2: "+result);
                else append("\nRobat2: "+result);
                status.setText("پاسخ با مدل محلی تولید شد؛ اتصال اینترنت استفاده نشد.");
                send.setEnabled(true);
            });
        });
    }

    private void append(String value) { conversation.append(value); }
    private LinearLayout.LayoutParams params(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onDestroy() {
        worker.execute(LocalModelEngine::release);
        worker.shutdown();
        super.onDestroy();
    }
}
