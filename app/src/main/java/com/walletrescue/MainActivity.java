
package com.walletrescue;
import android.os.Bundle;import android.os.SystemClock;import android.widget.Button;import android.widget.EditText;import android.widget.ScrollView;import android.widget.TextView;import androidx.appcompat.app.AppCompatActivity;import java.util.ArrayList;import java.util.List;import java.util.Locale;

public class MainActivity extends AppCompatActivity{
 private EditText inputText;private TextView outputText,statusText,speedText;private Button startBtn,stopBtn;private ScrollView outputScroll;private volatile boolean scanCancelled=false,scanning=false;private long t0;

 @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);inputText=findViewById(R.id.inputText);outputText=findViewById(R.id.outputText);statusText=findViewById(R.id.statusText);speedText=findViewById(R.id.speedText);startBtn=findViewById(R.id.startBtn);stopBtn=findViewById(R.id.stopBtn);outputScroll=findViewById(R.id.outputScroll);stopBtn.setEnabled(false);startBtn.setOnClickListener(v->startScan());stopBtn.setOnClickListener(v->stopScan());}

 private void startScan(){if(scanning)return;List<String> list=new ArrayList<>();for(String s:inputText.getText().toString().split("[\\r\\n,;\\s]+"))if(!s.trim().isEmpty())list.add(s.trim());if(list.isEmpty()){statusText.setText("ابتدا آدرس عمومی بیت‌کوین را وارد کنید.");return;}if(list.size()>100){statusText.setText("حداکثر ۱۰۰ آدرس مجاز است.");return;}
 scanCancelled=false;scanning=true;startBtn.setEnabled(false);stopBtn.setEnabled(true);outputText.setText("بررسی آدرس‌های عمومی BTC آغاز شد...");statusText.setText("در حال بررسی بیت‌کوین...");speedText.setText("۰ از "+fmt(list.size()));t0=SystemClock.elapsedRealtime();
 new Thread(()->{try{String report=AddressScanner.scan(this,list,0,(done,total)->runOnUiThread(()->{if(!scanCancelled){long ms=Math.max(1,SystemClock.elapsedRealtime()-t0);statusText.setText("BTC — "+done+" از "+total);speedText.setText(fmt(done)+" / "+fmt(total)+" آدرس | "+fmt(done*1000L/ms)+" آدرس/ثانیه");}}),()->scanCancelled);runOnUiThread(()->{outputText.setText(report);statusText.setText(scanCancelled?"بررسی متوقف شد.":"بررسی بیت‌کوین تمام شد.");speedText.setText(" ");finishScan();});}catch(Exception e){runOnUiThread(()->{statusText.setText(scanCancelled?"بررسی متوقف شد.":"خطا در بررسی BTC");outputText.append("\n"+(e.getMessage()==null?e.toString():e.getMessage()));speedText.setText(" ");finishScan();});}}).start();}

 private void stopScan(){scanCancelled=true;statusText.setText("درخواست توقف بررسی ارسال شد...");}
 private void finishScan(){scanning=false;startBtn.setEnabled(true);stopBtn.setEnabled(false);}
 private static String fmt(long n){return String.format(Locale.US,"%,d",n);}

 private String getWalletAddress(String walletId) {
     // دریافت و برگرداندن شناسه کیف پول
     return walletId;
 }
}
