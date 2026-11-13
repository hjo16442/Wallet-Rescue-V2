package com.walletrescue;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;

public class BalanceChecker {

    private static final String BASE_URL = "https://blockchain.info/q/addressbalance/";
    private final OkHttpClient client = new OkHttpClient();

    public interface BalanceCallback {
        void onResult(long balance);
        void onError(String message);
    }

    public void checkBalance(String address, BalanceCallback callback) {
        String url = BASE_URL + address;
        
        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("خطا در ارتباط: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        // پاسخ API معمولاً فقط یک عدد است (به ساتوشی)
                        String responseBody = response.body().string();
                        long balanceSatoshis = Long.parseLong(responseBody.trim());
                        callback.onResult(balanceSatoshis);
                    } catch (Exception e) {
                        callback.onError("خطا در پردازش پاسخ");
                    }
                } else {
                    callback.onError("خطای سرور: " + response.code());
                }
            }
        });
    }
}
