package com.walletrescue;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import android.graphics.Color;
import org.json.JSONObject;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.*;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    EditText addressInput;
    TextView result;
    Spinner networkSpinner;
    Button checkButton;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 35, 28, 25);
        layout.setBackgroundColor(Color.rgb(15, 23, 42));

        TextView title = new TextView(this);
        title.setText("WALLET RESCUE V2");
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Safe Wallet Address Checker");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(15);
        layout.addView(subtitle);

        TextView label = new TextView(this);
        label.setText("Select Network");
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 30, 0, 8);
        layout.addView(label);

        networkSpinner = new Spinner(this);
        String[] networks = {"Bitcoin (BTC)", "Ethereum (ETH)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, networks);
        networkSpinner.setAdapter(adapter);
        layout.addView(networkSpinner);

        addressInput = new EditText(this);
        addressInput.setHint("Enter public wallet address");
        addressInput.setTextColor(Color.WHITE);
        addressInput.setHintTextColor(Color.LTGRAY);
        addressInput.setSingleLine(true);
        addressInput.setPadding(12, 15, 12, 15);
        layout.addView(addressInput);

        checkButton = new Button(this);
        checkButton.setText("CHECK WALLET");
        layout.addView(checkButton);

        result = new TextView(this);
        result.setTextColor(Color.WHITE);
        result.setTextSize(17);
        result.setPadding(0, 25, 0, 0);
        layout.addView(result);

        TextView safety = new TextView(this);
        safety.setText(
            "\nSECURITY NOTICE\n" +
            "Only public addresses are accepted.\n" +
            "Never enter your seed phrase or private key."
        );
        safety.setTextColor(Color.rgb(100, 220, 160));
        safety.setTextSize(14);
        layout.addView(safety);

        setContentView(layout);

        checkButton.setOnClickListener(v -> checkWallet());
    }

    void checkWallet() {
        String address = addressInput.getText().toString().trim();
        int network = networkSpinner.getSelectedItemPosition();

        if (address.isEmpty()) {
            result.setText("Please enter a wallet address.");
            return;
        }

        if (network == 0 &&
            !(address.startsWith("1") ||
              address.startsWith("3") ||
              address.toLowerCase().startsWith("bc1"))) {
            result.setText("Invalid Bitcoin address format.");
            return;
        }

        if (network == 1 &&
            !(address.matches("(?i)^0x[0-9a-f]{40}$"))) {
            result.setText("Invalid Ethereum address format.");
            return;
        }

        checkButton.setEnabled(false);
        result.setText("Checking wallet...");

        executor.execute(() -> {
            String response;

            try {
                if (network == 0) {
                    response = checkBitcoin(address);
                } else {
                    response = checkEthereum(address);
                }
            } catch (Exception e) {
                response = "Connection error. Please try again.";
            }

            final String finalResponse = response;

            runOnUiThread(() -> {
                result.setText(finalResponse);
                checkButton.setEnabled(true);
            });
        });
    }

    String checkBitcoin(String address) throws Exception {
        String url = "https://mempool.space/api/address/" + address;
        JSONObject data = request(url);

        JSONObject stats = data.getJSONObject("chain_stats");

        long received = stats.getLong("funded_txo_sum");
        long spent = stats.getLong("spent_txo_sum");

        BigDecimal balance = BigDecimal.valueOf(received - spent)
            .divide(BigDecimal.valueOf(100000000));

        return "BITCOIN WALLET\n\n" +
            "Address:\n" + address + "\n\n" +
            "Confirmed balance:\n" + balance.toPlainString() + " BTC";
    }

    String checkEthereum(String address) throws Exception {
        URL url = new URL("https://cloudflare-eth.com");
        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty(
            "Content-Type", "application/json");

        String body =
            "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\"," +
            "\"params\":[\"" + address + "\",\"latest\"],\"id\":1}";

        OutputStream out = connection.getOutputStream();
        out.write(body.getBytes("UTF-8"));
        out.close();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream()));

        StringBuilder text = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            text.append(line);
        }

        reader.close();
        connection.disconnect();

        JSONObject json = new JSONObject(text.toString());

        if (json.has("error")) {
            throw new Exception("Ethereum RPC error");
        }

        String hex = json.getString("result");

        BigInteger wei = new BigInteger(hex.substring(2), 16);

        BigDecimal eth = new BigDecimal(wei)
            .divide(new BigDecimal("1000000000000000000"),
                18, RoundingMode.DOWN);

        return "ETHEREUM WALLET\n\n" +
            "Address:\n" + address + "\n\n" +
            "Balance:\n" + eth.toPlainString() + " ETH";
    }

    JSONObject request(String urlText) throws Exception {
        URL url = new URL(urlText);
        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream()));

        StringBuilder text = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            text.append(line);
        }

        reader.close();
        connection.disconnect();

        return new JSONObject(text.toString());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}