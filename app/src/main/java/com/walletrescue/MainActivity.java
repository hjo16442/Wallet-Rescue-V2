package com.walletrescue;

import android.os.Bundle;
import android.os.SystemClock;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity{
 private EditText inputText;
 private TextView outputText,statusText,speedText,walletText,progressText;
 private Button startBtn,stopBtn,create12Btn,create24Btn,copyBtn,newWalletBtn;
 private ScrollView outputScroll;
 private volatile boolean scanCancelled=false,scanning=false, walletCreated=false;
 private long t0;
 private WalletCore.WalletResult currentWallet;

 @Override protected void onCreate(Bundle b){
  super.onCreate(b);
  setContentView(R.layout.activity_main);
  inputText=findViewById(R.id.inputText);
  outputText=findViewById(R.id.outputText);
  statusText=findViewById(R.id.statusText);
  speedText=findViewById(R.id.speedText);
  walletText=findViewById(R.id.walletText);
  startBtn=findViewById(R.id.startBtn);
  stopBtn=findViewById(R.id.stopBtn);
  outputScroll=findViewById(R.id.outputScroll);
  create12Btn=findViewById(R.id.create12Btn);
  create24Btn=findViewById(R.id.create24Btn);
  copyBtn=findViewById(R.id.copyBtn);

  // اضافه کردن دکمه ساخت کیف پول جدید
  newWalletBtn=new Button(this);
  newWalletBtn.setText("ساخت کیف پول جدید");
  newWalletBtn.setEnabled(false);
  ((android.widget.LinearLayout) findViewById(R.id.outputScroll).getParent()).addView(newWalletBtn, 3);

  stopBtn.setEnabled(false);
  copyBtn.setEnabled(false);
  startBtn.setEnabled(true);

  startBtn.setOnClickListener(v->startScan());
  stopBtn.setOnClickListener(v->stopScan());
  create12Btn.setOnClickListener(v->createWallet(12));
  create24Btn.setOnClickListener(v->createWallet(24));
  copyBtn.setOnClickListener(v->copyMnemonic());
  newWalletBtn.setOnClickListener(v->resetWallet());
 }

 private void createWallet(int count){
  if(walletCreated){
   statusText.setText("کیف پول قبلاً ساخته شده است. برای ساخت کیف پول جدید، ابتدا روی دکمه 'ساخت کیف پول جدید' بزنید.");
   return;
  }
  create12Btn.setEnabled(false);
  create24Btn.setEnabled(false);
  startBtn.setEnabled(false);
  statusText.setText("در حال تولید کیف پول آفلاین روی دستگاه…");
  walletText.setText("");
  copyBtn.setEnabled(false);
  newWalletBtn.setEnabled(false);
  progressText = findViewById(R.id.statusText);
  new Thread(()->{
   try{
    WalletCore.WalletResult w=WalletCore.create(count);
    currentWallet=w;
    walletCreated=true;
    StringBuilder report=new StringBuilder();
    report.append("کیف پول جدید ساخته شد — ").append(count).append(" کلمه\n\n");
    report.append("عبارت بازیابی (آن را با هیچ‌کس به اشتراک نگذارید):\n").append(w.mnemonic()).append("\n\n");
    report.append("Bitcoin BTC\nآدرس: ").append(w.btc).append("\nمسیر: ").append(w.btcPath).append("\n\n");
    report.append("Ethereum ETH\nآدرس: ").append(w.eth).append("\nمسیر: ").append(w.ethPath).append("\n\n");
    report.append("TRON / TRC20\nآدرس: ").append(w.tron).append("\nمسیر: ").append(w.tronPath).append("\n\n");
    report.append("موجودی‌ها هنوز بررسی نشده‌اند. برای بررسی BTC، آدرس را در بخش پایین وارد کنید.\n\n");
    report.append("هشدار: عبارت بازیابی و کلید خصوصی فقط روی دستگاه نگهداری می‌شوند و به اینترنت ارسال نمی‌شوند.");
    runOnUiThread(()->{
     walletText.setText(report.toString());
     statusText.setText("کیف پول به‌صورت آفلاین ساخته شد.");
     copyBtn.setEnabled(true);
     newWalletBtn.setEnabled(true);
    });
   }catch(Exception e){
    runOnUiThread(()->statusText.setText("خطا در تولید کیف پول: "+(e.getMessage()==null?e.toString():e.getMessage())));
   }finally{
    runOnUiThread(()->{
     create12Btn.setEnabled(true);
     create24Btn.setEnabled(true);
     startBtn.setEnabled(true);
    });
   }
  }).start();
 }

 private void resetWallet(){
  walletCreated=false;
  currentWallet=null;
  walletText.setText("");
  copyBtn.setEnabled(false);
  newWalletBtn.setEnabled(false);
  statusText.setText("کیف پول پاک شد. برای ساخت کیف پول جدید روی دکمه ۱۲ یا ۲۴ کلمه‌ای بزنید.");
 }

 private void copyMnemonic(){
  if(currentWallet==null) return;
  ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
  cm.setPrimaryClip(ClipData.newPlainText("BIP39 recovery phrase",currentWallet.mnemonic()));
  Toast.makeText(this,"عبارت بازیابی کپی شد؛ کلیپ‌بورد را امن نگه‌دار و پاک کن.",Toast.LENGTH_LONG).show();
 }

 private void startScan(){
  if(scanning) return;
  List<String> list=new ArrayList<>();
  for(String s:inputText.getText().toString().split("[\r\n,;\s]+"))
   if(!s.trim().isEmpty()) list.add(s.trim());
  if(list.isEmpty()){
   // اگر آدرس وارد نشده، از آدرس کیف پول ساخته شده استفاده شود
   if(currentWallet!=null && currentWallet.btc!=null && !currentWallet.btc.isEmpty()){
    list.add(currentWallet.btc);
   } else {
    statusText.setText("برای بررسی، آدرس عمومی BTC را وارد کنید یا ابتدا کیف پول بسازید.");
    return;
   }
  }
  if(list.size()>100){
   statusText.setText("حداکثر ۱۰۰ آدرس مجاز است.");
   return;
  }
  scanCancelled=false;
  scanning=true;
  startBtn.setEnabled(false);
  stopBtn.setEnabled(true);
  outputText.setText("بررسی آدرس‌های عمومی BTC آغاز شد…");
  statusText.setText("در حال بررسی BTC…");
  speedText.setText("۰ از "+fmt(list.size()));
  t0=SystemClock.elapsedRealtime();
  new Thread(()->{
   try{
    String report=AddressScanner.scan(this,list,0,(done,total)->runOnUiThread(()->{
     if(!scanCancelled){
      long ms=Math.max(1,SystemClock.elapsedRealtime()-t0);
      statusText.setText("BTC — "+done+" از "+total);
      speedText.setText(fmt(done)+" / "+fmt(total)+" آدرس | "+fmt(done*1000L/ms)+" آدرس/ثانیه");
     }
    }),()->scanCancelled);
    runOnUiThread(()->{
     outputText.setText(report);
     statusText.setText(scanCancelled?"بررسی متوقف شد.":"بررسی BTC تمام شد.");
     speedText.setText(" ");
     finishScan();
    });
   }catch(Exception e){
    runOnUiThread(()->{
     statusText.setText(scanCancelled?"بررسی متوقف شد.":"خطا در بررسی BTC");
     outputText.append("\n"+(e.getMessage()==null?e.toString():e.getMessage()));
     speedText.setText(" ");
     finishScan();
    });
   }
  }).start();
 }

 private void stopScan(){
  scanCancelled=true;
  statusText.setText("درخواست توقف بررسی ارسال شد…");
 }

 private void finishScan(){
  scanning=false;
  startBtn.setEnabled(true);
  stopBtn.setEnabled(false);
 }

 private static String fmt(long n){
  return String.format(Locale.US,"%,d",n);
 }
}
