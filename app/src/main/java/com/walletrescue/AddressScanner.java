package com.walletrescue;
import org.json.JSONObject;
import java.io.BufferedReader;import java.io.InputStream;import java.io.InputStreamReader;
import java.math.BigDecimal;import java.math.RoundingMode;import java.net.HttpURLConnection;import java.net.URL;
import java.util.ArrayList;import java.util.List;import java.util.concurrent.ExecutorCompletionService;import java.util.concurrent.ExecutorService;import java.util.concurrent.Executors;import java.util.concurrent.Future;import java.util.regex.Pattern;
public final class AddressScanner {
 private static final int MAX_ADDRESSES=100,MAX_WORKERS=4;
 private static final Pattern BTC_LEGACY=Pattern.compile("^(1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}$");
 private static final Pattern BTC_SEGWIT=Pattern.compile("^(bc1)[ac-hj-np-z02-9]{11,71}$",Pattern.CASE_INSENSITIVE);
 private AddressScanner(){}
 public interface Progress{void onProgress(int completed,int total);} public interface CancelCheck{boolean isCancelled();}
 public static String scan(MainActivity a,List<String> addresses,int network)throws Exception{return scan(a,addresses,network,(d,t)->{},()->false);}
 public static String scan(MainActivity a,List<String> addresses,int network,Progress progress,CancelCheck cancel)throws Exception{
  if(addresses==null||addresses.isEmpty())throw new Exception("هیچ آدرسی وارد نشده است.");
  if(addresses.size()>MAX_ADDRESSES)throw new Exception("حداکثر ۱۰۰ آدرس در هر نوبت مجاز است.");
  if(network!=0)throw new Exception("این نسخه فقط BTC را پشتیبانی می‌کند.");
  List<String> valid=new ArrayList<>();StringBuilder out=new StringBuilder();
  for(String raw:addresses){String addr=raw==null?"":raw.trim();if(addr.isEmpty())continue;if(!validAddress(addr))out.append("آدرس BTC نامعتبر:\\n").append(addr).append("\\n------------------\\n");else valid.add(addr);}
  if(valid.isEmpty())return out.append("\\nهیچ آدرس معتبر BTC وجود ندارد.").toString();
  ExecutorService pool=Executors.newFixedThreadPool(Math.min(MAX_WORKERS,valid.size()));ExecutorCompletionService<String> cs=new ExecutorCompletionService<>(pool);
  try{for(String addr:valid)cs.submit(()->{if(cancel.isCancelled())return addr+"\\nبررسی متوقف شد.";try{return checkBitcoin(addr);}catch(Exception e){return addr+"\\nموجودی نامشخص؛ خطا: "+(e.getMessage()==null?"خطای شبکه":e.getMessage());}});
   int done=0;while(done<valid.size()){if(cancel.isCancelled())break;Future<String> f=cs.take();out.append(f.get()).append("\\n\\n------------------\\n");progress.onProgress(++done,valid.size());}
   if(done<valid.size())out.append("\\nبررسی متوقف شد.\\n");out.append("\\nآدرس‌های بررسی‌شده: ").append(done).append(" از ").append(valid.size());
  }finally{pool.shutdownNow();}
  return out.append("\\nاین ابزار فقط اطلاعات عمومی شبکه BTC را می‌خواند و تراکنش انجام نمی‌دهد.").toString();
 }
 private static boolean validAddress(String a){return BTC_LEGACY.matcher(a).matches()||BTC_SEGWIT.matcher(a).matches();}
 private static String checkBitcoin(String address)throws Exception{
  String[] endpoints={"https://mempool.space/api/address/","https://blockstream.info/api/address/"};Exception last=null;
  for(String ep:endpoints)try{JSONObject j=getJson(ep+address);JSONObject chain=j.optJSONObject("chain_stats"),mp=j.optJSONObject("mempool_stats");if(chain==null||mp==null)throw new Exception("پاسخ ناقص API");
   long confirmed=chain.getLong("funded_txo_sum")-chain.getLong("spent_txo_sum"),pending=mp.getLong("funded_txo_sum")-mp.getLong("spent_txo_sum");
   BigDecimal c=sats(confirmed),p=sats(pending);return "Bitcoin: "+address+"\\nموجودی تأییدشده: "+c.toPlainString()+" BTC\\nدر انتظار تأیید: "+p.toPlainString()+" BTC\\nمجموع: "+c.add(p).toPlainString()+" BTC\\nتراکنش‌های تأییدشده: "+chain.optLong("tx_count",0)+"\\nAPI: "+ep;
  }catch(Exception e){last=e;}throw new Exception("APIهای BTC پاسخ ندادند: "+(last==null?"":last.getMessage()));
 }
 private static BigDecimal sats(long n){return BigDecimal.valueOf(n).divide(new BigDecimal("100000000"),8,RoundingMode.DOWN);}
 private static JSONObject getJson(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();try{c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","WalletRescue-BTC/3.0");int code=c.getResponseCode();InputStream s=code<400?c.getInputStream():c.getErrorStream();if(s==null)throw new Exception("HTTP "+code);StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(s,"UTF-8"))){String line;while((line=r.readLine())!=null)b.append(line);}if(code>=400)throw new Exception("HTTP "+code);return new JSONObject(b.toString());}finally{c.disconnect();}}
}