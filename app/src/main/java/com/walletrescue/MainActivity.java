package com.walletrescue;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.math.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {

    EditText addressInput;
    TextView result;
    Spinner networkSpinner;
    Button checkButton, historyButton;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(28, 35, 28, 25);
        layout.setBackgroundColor(Color.rgb(15, 23, 42));

        TextView title = new TextView(this);
        title.setText("WALLET RESCUE V2.1");
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        layout.addView(title);

        TextView label = new TextView(this);
        label.setText("Select Network");
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 25, 0, 8);
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
        layout.addView(addressInput);

        checkButton = new Button(this);
        checkButton.setText("CHECK WALLET");
        layout.addView(checkButton);

        historyButton = new Button(this);
        historyButton.setText("BITCOIN TRANSACTION HISTORY");
        layout.addView(historyButton);

        result = new TextView(this);
        result.setTextColor(Color.WHITE);
        result.setTextSize(15);
        result.setPadding(0, 20, 0, 0);
        layout.addView(result);

        TextView safety = new TextView(this);
        safety.setText(
            "\nSECURITY NOTICE\n" +
            "Only public addresses are accepted.\n" +
            "Never enter your seed phrase or private key."
        );
        safety.setTextColor(Color.rgb(100, 220, 160));
        layout.addView(safety);

        setContentView(layout);

        checkButton.setOnClickListener(v -> checkWallet());
        historyButton.setOnClickListener(v -> loadHistory());
    }

    void checkWallet() {
        String address = addressInput.getText().toString().trim();
        int network = networkSpinner.getSelectedItemPosition();

        if (address.isEmpty()) {
            result.setText("Enter a public address.");
            return;
        }

        if (network == 0 &&
            !(address.startsWith("1") ||
              address.startsWith("3") ||
              address.toLowerCase().startsWith("bc1"))) {
            result.setText("Invalid Bitcoin address.");
            return;
        }

        if (network == 1 &&
            !address.matches("(?i)^0x[0-9a-f]{40}$")) {
            result.setText("Invalid Ethereum address.");
            return;
        }

        runTask(() -> {
            if (network == 0) {
                JSONObject data = getJson(
                    "https://mempool.space/api/address/" + address);

                JSONObject stats = data.getJSONObject("chain_stats");

                long received = stats.getLong("funded_txo_sum");
                long spent = stats.getLong("spent_txo_sum");

                BigDecimal balance = BigDecimal.valueOf(received - spent)
                    .divide(BigDecimal.valueOf(100000000));

                return "BITCOIN WALLET\n\nAddress:\n" +
                    address + "\n\nConfirmed balance:\n" +
                    balance.toPlainString() + " BTC";
            } else {
                return checkEthereum(address);
            }
        });
    }

    void loadHistory() {
        String address = addressInput.getText().toString().trim();

        if (address.isEmpty()) {
            result.setText("Enter a Bitcoin public address.");
            return;
        }

        if (!(address.startsWith("1") ||
              address.startsWith("3") ||
              address.toLowerCase().startsWith("bc1"))) {
            result.setText("Enter a valid Bitcoin address.");
            return;
        }

        runTask(() -> {
            String url = "https://mempool.space/api/address/" +
                address + "/txs";

            JSONArray transactions = getArray(url);

            StringBuilder output = new StringBuilder();
            output.append("BITCOIN TRANSACTION HISTORY\n\n");

            if (transactions.length() == 0) {
                output.append("No transactions found.");
                return output.toString();
            }

            int count = Math.min(10, transactions.length());

            for (int i = 0; i < count; i++) {
                JSONObject tx = transactions.getJSONObject(i);
                String txid = tx.getString("txid");

                JSONObject status = tx.getJSONObject("status");

                output.append("Transaction ")
                    .append(i + 1).append("\n");

                output.append("ID:\n")
                    .append(txid).append("\n");

                if (status.optBoolean("confirmed")) {
                    long time = status.optLong("block_time", 0);
                    output.append("Date: ")
                        .append(new java.util.Date(time * 1000L))
                        .append("\n");
                } else {
                    output.append("Status: Unconfirmed\n");
                }

                output.append("--------------------\n");
            }

            return output.toString();
        });
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

        String response = readResponse(connection);
        JSONObject json = new JSONObject(response);
        connection.disconnect();

        if (json.has("error")) {
            throw new Exception("Ethereum RPC error");
        }

        String hex = json.getString("result");
        BigInteger wei = new BigInteger(hex.substring(2), 16);

        BigDecimal eth = new BigDecimal(wei).divide(
            new BigDecimal("1000000000000000000"),
            18, RoundingMode.DOWN);

        return "ETHEREUM WALLET\n\nAddress:\n" +
            address + "\n\nBalance:\n" +
            eth.toPlainString() + " ETH";
    }

    void runTask(Callable<String> task) {
        checkButton.setEnabled(false);
        historyButton.setEnabled(false);
        result.setText("Please wait...");

        executor.execute(() -> {
            String response;

            try {
                response = task.call();
            } catch (Exception e) {
                response = "Connection error.\nPlease try again.";
            }

            final String message = response;

            runOnUiThread(() -> {
                result.setText(message);
                checkButton.setEnabled(true);
                historyButton.setEnabled(true);
            });
        });
    }

    JSONObject getJson(String url) throws Exception {
        return new JSONObject(getText(url));
    }

    JSONArray getArray(String url) throws Exception {
        return new JSONArray(getText(url));
    }

    String getText(String urlText) throws Exception {
        URL url = new URL(urlText);
        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        String response = readResponse(connection);
        connection.disconnect();
        return response;
    }

    String readResponse(HttpURLConnection connection)
        throws Exception {

        InputStream stream =
            connection.getResponseCode() < 400
            ? connection.getInputStream()
            : connection.getErrorStream();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, "UTF-8"));

        StringBuilder text = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            text.append(line);
        }

        reader.close();
        return text.toString();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
