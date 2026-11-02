package com.walletrescue;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.math.*;
import java.util.Date;
import java.util.concurrent.*;

public class MainActivity extends Activity {

    EditText addressInput;
    TextView result;
    Spinner networkSpinner;
    Button checkButton, historyButton, scanButton;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    static final int PICK_WALLET_FILE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(28, 35, 28, 25);
        layout.setBackgroundColor(Color.rgb(15, 23, 42));

        TextView title = new TextView(this);
        title.setText("WALLET RESCUE V3");
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

        String[] networks = {
            "Bitcoin (BTC)",
            "Ethereum (ETH)",
            "TRON (TRX)"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            networks
        );

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
        historyButton.setText("TRANSACTION HISTORY");
        layout.addView(historyButton);

        scanButton = new Button(this);
        scanButton.setText("SCAN WALLET FILES");
        layout.addView(scanButton);

        result = new TextView(this);
        result.setTextColor(Color.WHITE);
        result.setTextSize(15);
        result.setPadding(0, 20, 0, 0);
        layout.addView(result);

        TextView safety = new TextView(this);
        safety.setText(
            "\nSECURITY NOTICE\n" +
            "Only public addresses are used online.\n" +
            "Selected files are identified locally.\n" +
            "Never share your seed phrase or private key."
        );
        safety.setTextColor(Color.rgb(100, 220, 160));
        layout.addView(safety);

        scroll.addView(layout);
        setContentView(scroll);

        checkButton.setOnClickListener(v -> checkWallet());
        historyButton.setOnClickListener(v -> loadHistory());
        scanButton.setOnClickListener(v -> selectWalletFile());
    }

    void selectWalletFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_WALLET_FILE);
    }

    @Override
    protected void onActivityResult(
        int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_WALLET_FILE &&
            resultCode == RESULT_OK && data != null) {

            Uri uri = data.getData();
            if (uri == null) return;

            String fileName = getFileName(uri);
            String lower = fileName.toLowerCase();

            String type = "Unknown wallet file";

            if (lower.equals("wallet.dat")) {
                type = "Bitcoin Core wallet.dat";
            } else if (lower.startsWith("utc--")) {
                type = "Ethereum Keystore";
            } else if (lower.endsWith(".json")) {
                type = "JSON wallet backup";
            } else if (lower.endsWith(".txt")) {
                type = "Text backup file";
            }

            result.setText(
                "FILE SCAN RESULT\n\n" +
                "Name: " + fileName + "\n\n" +
                "Detected type:\n" + type +
                "\n\nFile identification only. " +
                "Validity and recoverability are not verified."
            );
        }
    }

    String getFileName(Uri uri) {
        String name = "Selected file";

        try (Cursor cursor = getContentResolver()
            .query(uri, null, null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME);

                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {}

        return name;
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
            result.setText("Invalid Bitcoin address format.");
            return;
        }

        if (network == 1 &&
            !address.matches("(?i)^0x[0-9a-f]{40}$")) {
            result.setText("Invalid Ethereum address.");
            return;
        }

        if (network == 2 &&
            !address.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            result.setText("Invalid TRON address format.");
            return;
        }

        runTask(() -> {
    if (network == 0) {
        return checkBitcoin(address);
    } else if (network == 1) {
        return checkEthereum(address);
    } else {
        return checkTron(address);
    }
});
    }

    String checkBitcoin(String address) throws Exception {
        JSONObject data = getJson(
            "https://mempool.space/api/address/" + address);

        JSONObject stats = data.getJSONObject("chain_stats");

        long received = stats.getLong("funded_txo_sum");
        long spent = stats.getLong("spent_txo_sum");

        BigDecimal balance = BigDecimal.valueOf(received - spent)
            .divide(new BigDecimal("100000000"));

        return "BITCOIN WALLET\n\nAddress:\n" +
            address + "\n\nConfirmed balance:\n" +
            balance.toPlainString() + " BTC";
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

        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes("UTF-8"));
        }

        String response = readResponse(connection);
        connection.disconnect();

        JSONObject json = new JSONObject(response);

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

    String checkTron(String address) throws Exception {
        JSONObject json = getJson(
            "https://api.trongrid.io/v1/accounts/" + address);

        JSONArray accounts = json.optJSONArray("data");

        if (accounts == null || accounts.length() == 0) {
            return "TRON WALLET\n\nAddress:\n" +
                address + "\n\nAccount not found or no account data.";
        }

        JSONObject account = accounts.getJSONObject(0);

        long sun = account.optLong("balance", 0);

        BigDecimal trx = BigDecimal.valueOf(sun)
            .divide(new BigDecimal("1000000"));

        return "TRON WALLET\n\nAddress:\n" +
            address + "\n\nBalance:\n" +
            trx.toPlainString() + " TRX";
    }

    void loadHistory() {
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

        if (network == 2 &&
            !address.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            result.setText("Invalid TRON address.");
            return;
        }

        runTask(() -> {
            if (network == 0) {
                return bitcoinHistory(address);
            } else if (network == 2) {
                return tronHistory(address);
            } else {
                return "Ethereum transaction history is not enabled yet.";
            }
        });
    }

    String bitcoinHistory(String address) throws Exception {
        JSONArray transactions = getArray(
            "https://mempool.space/api/address/" +
            address + "/txs");

        StringBuilder output = new StringBuilder();
        output.append("BITCOIN TRANSACTION HISTORY\n\n");

        if (transactions.length() == 0) {
            return "No Bitcoin transactions found.";
        }

        int count = Math.min(10, transactions.length());

        for (int i = 0; i < count; i++) {
            JSONObject tx = transactions.getJSONObject(i);
            JSONObject status = tx.getJSONObject("status");

            output.append("Transaction ")
                .append(i + 1).append("\nID:\n")
                .append(tx.getString("txid")).append("\n");

            if (status.optBoolean("confirmed")) {
                long time = status.optLong("block_time", 0);
                output.append("Date: ")
                    .append(new Date(time * 1000L))
                    .append("\n");
            } else {
                output.append("Status: Unconfirmed\n");
            }

            output.append("--------------------\n");
        }

        return output.toString();
    }

    String tronHistory(String address) throws Exception {
        JSONArray transactions = getJson(
            "https://api.trongrid.io/v1/accounts/" +
            address +
            "/transactions?limit=10&only_confirmed=true"
        ).optJSONArray("data");

        StringBuilder output = new StringBuilder();
        output.append("TRON TRANSACTION HISTORY\n\n");

        if (transactions == null || transactions.length() == 0) {
            return "No confirmed TRON transactions found.";
        }

        for (int i = 0; i < transactions.length(); i++) {
            JSONObject tx = transactions.getJSONObject(i);

            output.append("Transaction ")
                .append(i + 1).append("\nID:\n")
                .append(tx.optString("txID", "Unknown"))
                .append("\n");

            long time = tx.optLong("block_timestamp", 0);

            if (time > 0) {
                output.append("Date: ")
                    .append(new Date(time))
                    .append("\n");
            }

            JSONArray contracts = tx.optJSONObject("raw_data")
                .optJSONArray("contract");

            if (contracts != null && contracts.length() > 0) {
                output.append("Type: ")
                    .append(contracts.getJSONObject(0)
                    .optString("type", "Unknown"))
                    .append("\n");
            }

            output.append("--------------------\n");
        }

        return output.toString();
    }

    void runTask(Callable<String> task) {
        checkButton.setEnabled(false);
        historyButton.setEnabled(false);
        scanButton.setEnabled(false);
        result.setText("Please wait...");

        executor.execute(() -> {
            String response;

            try {
                response = task.call();
            } catch (Exception e) {
                response =
                    "Connection error.\n" +
                    "The network service may be unavailable.\n" +
                    "Please try again.";
            }

            final String message = response;

            runOnUiThread(() -> {
                result.setText(message);
                checkButton.setEnabled(true);
                historyButton.setEnabled(true);
                scanButton.setEnabled(true);
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

        if (stream == null) {
            throw new Exception("Empty server response");
        }

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
