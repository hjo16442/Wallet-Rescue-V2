package com.walletrescue;

import android.app.Activity;
import android.app.AlertDialog;
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
    Button checkButton, historyButton, scanButton, recoveryButton, bulkScanButton, generateButton;
    Button autoScanButton, cancelScanButton;
    volatile boolean scanCancelled = false;
    volatile String autoScanHits = "";
    volatile String autoScanProgress = "";

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
        title.setText("WALLET RESCUE V3.2");
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

        bulkScanButton = new Button(this);
        bulkScanButton.setText("BULK ADDRESS SCAN");
        layout.addView(bulkScanButton);
        bulkScanButton.setOnClickListener(v -> showBulkScanDialog());

        recoveryButton = new Button(this);
        recoveryButton.setText("RECOVER WALLET");
        layout.addView(recoveryButton);
        recoveryButton.setOnClickListener(v -> showRecoveryDialog());

        autoScanButton = new Button(this);
        autoScanButton.setText("AUTO SCAN — MY WALLET");
        layout.addView(autoScanButton);
        autoScanButton.setOnClickListener(v -> showAutoScanDialog());
        
        cancelScanButton = new Button(this);
        cancelScanButton.setText("لغو اسکن");
        cancelScanButton.setEnabled(false);
        layout.addView(cancelScanButton);
        cancelScanButton.setOnClickListener(v -> {
            scanCancelled = true;
            autoScanProgress = "درخواست توقف ارسال شد؛ بعد از درخواست جاری متوقف می‌شود.";
            renderAutoScanStatus();
        });


        generateButton = new Button(this);
generateButton.setText("GENERATE NEW WALLET");
layout.addView(generateButton);
generateButton.setOnClickListener(v -> showGeneratePhraseDialog());

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
        result.setTextIsSelectable(true); // Long-press an address in results to select and copy it.
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

    boolean validAddress(String address, int network) {
        if (network == 0) {
            return address.startsWith("1") ||
                address.startsWith("3") ||
                address.toLowerCase().startsWith("bc1");
        }

        if (network == 1) {
            return address.matches("(?i)^0x[0-9a-f]{40}$");
        }

        return address.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$");
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
                    String found = cursor.getString(index);
                    if (found != null && !found.isEmpty()) {
                        name = found;
                    }
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

        if (!validAddress(address, network)) {
            result.setText("Invalid wallet address format.");
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

    void loadHistory() {
        String address = addressInput.getText().toString().trim();
        int network = networkSpinner.getSelectedItemPosition();

        if (address.isEmpty()) {
            result.setText("Enter a public address.");
            return;
        }

        if (!validAddress(address, network)) {
            result.setText("Invalid wallet address format.");
            return;
        }

        runTask(() -> {
            if (network == 0) {
                return bitcoinHistory(address);
            } else if (network == 1) {
                return ethereumHistory(address);
            } else {
                return tronHistory(address);
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

    // Ethereum balance using PublicNode RPC
    String checkEthereum(String address) throws Exception {

        URL url = new URL(
            "https://ethereum-rpc.publicnode.com");

        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);

            connection.setRequestProperty(
                "Content-Type", "application/json");

            connection.setRequestProperty(
                "Accept", "application/json");

            String body =
                "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\"," +
                "\"params\":[\"" + address + "\",\"latest\"],\"id\":1}";

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
                out.flush();
            }

            int statusCode = connection.getResponseCode();

            String response = readResponse(connection);

            if (statusCode >= 400) {
                throw new Exception(
                    "Ethereum RPC HTTP error: " + statusCode);
            }

            JSONObject json = new JSONObject(response);

            if (json.has("error")) {
                JSONObject error = json.optJSONObject("error");

                String message = error == null
                    ? "Unknown RPC error"
                    : error.optString("message", "Unknown RPC error");

                throw new Exception(message);
            }

            String hex = json.optString("result", "");

            if (!hex.startsWith("0x") || hex.length() < 3) {
                throw new Exception("Invalid Ethereum RPC response");
            }

            BigInteger wei = new BigInteger(hex.substring(2), 16);

            BigDecimal eth = new BigDecimal(wei).divide(
                new BigDecimal("1000000000000000000"),
                18, RoundingMode.DOWN);

            return "ETHEREUM WALLET\n\nAddress:\n" +
                address + "\n\nBalance:\n" +
                eth.toPlainString() + " ETH";

        } finally {
            connection.disconnect();
        }
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
                .append(tx.optString("txid", "Unknown"))
                .append("\n");

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

    String ethereumHistory(String address) throws Exception {
        String url =
            "https://eth.blockscout.com/api/v2/addresses/" +
            address + "/transactions";

        JSONObject json = getJson(url);
        JSONArray transactions = json.optJSONArray("items");

        if (transactions == null || transactions.length() == 0) {
            return "No Ethereum transactions found.";
        }

        StringBuilder output = new StringBuilder();
        output.append("ETHEREUM TRANSACTION HISTORY\n\n");

        int count = Math.min(10, transactions.length());

        for (int i = 0; i < count; i++) {
            JSONObject tx = transactions.getJSONObject(i);

            JSONObject from = tx.optJSONObject("from");
            JSONObject to = tx.optJSONObject("to");

            String fromAddress = from == null
                ? "Unknown" : from.optString("hash", "Unknown");

            String toAddress = to == null
                ? "Contract creation" : to.optString("hash", "Unknown");

            String value = tx.optString("value", "0");

            BigDecimal eth = new BigDecimal(value).divide(
                new BigDecimal("1000000000000000000"),
                18, RoundingMode.DOWN);

            output.append("Transaction ")
                .append(i + 1).append("\n");

            output.append("ID:\n")
                .append(tx.optString("hash", "Unknown"))
                .append("\n");

            output.append("Date: ")
                .append(tx.optString("timestamp", "Unknown"))
                .append("\n");

            output.append("From: ")
                .append(fromAddress).append("\n");

            output.append("To: ")
                .append(toAddress).append("\n");

            output.append("Value: ")
                .append(eth.toPlainString())
                .append(" ETH\n");

            output.append("Status: ")
                .append(tx.optString("status", "Unknown"))
                .append("\n");

            output.append("--------------------\n");
        }

        return output.toString();
    }

    String tronHistory(String address) throws Exception {
        JSONObject json = getJson(
            "https://api.trongrid.io/v1/accounts/" +
            address +
            "/transactions?limit=10&only_confirmed=true"
        );

        JSONArray transactions = json.optJSONArray("data");

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

            JSONObject rawData = tx.optJSONObject("raw_data");

            if (rawData != null) {
                JSONArray contracts = rawData.optJSONArray("contract");

                if (contracts != null && contracts.length() > 0) {
                    output.append("Type: ")
                        .append(contracts.getJSONObject(0)
                        .optString("type", "Unknown"))
                        .append("\n");
                }
            }

            output.append("--------------------\n");
        }

        return output.toString();
    }

    void runTask(Callable<String> task) {
        checkButton.setEnabled(false);
        historyButton.setEnabled(false);
        scanButton.setEnabled(false);
        recoveryButton.setEnabled(false);
        autoScanButton.setEnabled(false);
        result.setText("Please wait...");

        executor.execute(() -> {
            String response;

            try {
                response = task.call();
            } catch (Exception | LinkageError e) {
                String detail = e.getMessage();
                if (detail == null || detail.trim().isEmpty()) {
                    detail = e.getClass().getSimpleName();
                } else {
                    detail = e.getClass().getSimpleName() + ": " + detail;
                }
                response =
                    "اسکن با خطا متوقف شد؛ برنامه نباید بسته شود.\n" +
                    "جزئیات فنی: " + detail + "\n\n" +
                    "عبارت بازیابی را دوباره در چت یا جای دیگری وارد نکن.";
            }

            final String message = response;

            runOnUiThread(() -> {
                result.setText(message);
                checkButton.setEnabled(true);
                historyButton.setEnabled(true);
                scanButton.setEnabled(true);
                recoveryButton.setEnabled(true);
                autoScanButton.setEnabled(true);
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

        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            return readResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    String readResponse(HttpURLConnection connection)
        throws Exception {

        int code = connection.getResponseCode();

        InputStream stream = code < 400
            ? connection.getInputStream()
            : connection.getErrorStream();

        if (stream == null) {
            throw new Exception(
                "Empty server response. HTTP " + code);
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, "UTF-8"));

        StringBuilder text = new StringBuilder();
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        } finally {
            reader.close();
        }

        return text.toString();
    }

    // ✅ متد گم‌شده که باعث خطای کامپایل شده بود
    private void showBulkScanDialog() {
        final EditText input = new EditText(this);
        input.setHint("هر آدرس را در یک خط جدید وارد کنید");
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        new AlertDialog.Builder(this)
            .setTitle("Bulk Address Scan")
            .setMessage("چند آدرس (هر کدام در یک خط) وارد کنید.")
            .setView(input)
            .setPositiveButton("بررسی", (dialog, which) -> {
                String raw = input.getText().toString().trim();
                if (raw.isEmpty()) {
                    Toast.makeText(this,
                        "لیست آدرس خالی است", Toast.LENGTH_SHORT).show();
                    return;
                }

                final String[] lines = raw.split("\\r?\n");
                final int network = networkSpinner.getSelectedItemPosition();

                result.setText("در حال بررسی " + lines.length + " آدرس...");

                executor.execute(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("BULK SCAN RESULT\n\n");

                    for (String line : lines) {
                        String addr = line.trim();
                        if (addr.isEmpty()) continue;

                        sb.append(addr).append("\n");

                        if (!validAddress(addr, network)) {
                            sb.append("  → فرمت نامعتبر\n\n");
                            continue;
                        }

                        try {
                            if (network == 0) {
                                sb.append("  → ")
                                  .append(checkBitcoin(addr)
                                          .replaceAll(".*Balance:\\s*", ""))
                                  .append("\n\n");
                            } else if (network == 1) {
                                sb.append("  → ")
                                  .append(checkEthereum(addr)
                                          .replaceAll(".*Balance:\\s*", ""))
                                  .append("\n\n");
                            } else {
                                sb.append("  → ")
                                  .append(checkTron(addr)
                                          .replaceAll(".*Balance:\\s*", ""))
                                  .append("\n\n");
                            }
                        } catch (Exception e) {
                            sb.append("  → خطا: ")
                              .append(e.getMessage())
                              .append("\n\n");
                        }
                    }

                    final String out = sb.toString();
                    runOnUiThread(() -> result.setText(out));
                });
            })
            .setNegativeButton("انصراف", null)
            .show();
    }

    private void showAutoScanDialog() {
        EditText input = new EditText(this);
        input.setHint("عبارت بازیابی خودت (۱۲ یا ۲۴ کلمه)");
        input.setSingleLine(false);
        input.setMinLines(3);

        new AlertDialog.Builder(this)
            .setTitle("اسکن کیف پول خودت")
            .setMessage("عبارت فقط روی گوشی استفاده می‌شود. آن را در چت یا سایت دیگری وارد نکن.")
            .setView(input)
            .setPositiveButton("ادامه", (dialog, which) -> {
                String phrase = input.getText().toString().trim();
                if (!SafeMnemonicRecovery.isValidCompletePhrase(this, phrase)) {
                    result.setText("عبارت BIP39 معتبر نیست.");
                    return;
                }
                String[] options = {"100 آدرس در هر مسیر", "500 آدرس در هر مسیر", "1000 آدرس در هر مسیر"};
                new AlertDialog.Builder(this)
                    .setTitle("حجم اسکن")
                    .setItems(options, (d, selected) -> {
                        final int count = selected == 0 ? 100 : selected == 1 ? 500 : 1000;
                        scanCancelled = false;
                        cancelScanButton.setEnabled(true);
                        autoScanButton.setEnabled(false);
                        autoScanHits = "";
                        autoScanProgress = "اسکن شروع شد؛ آدرس‌های دارای موجودی به محض پیدا شدن نمایش داده می‌شوند.";
                        renderAutoScanStatus();
                        executor.execute(() -> {
                            String response;
                            try {
                                response = AutoWalletScanner.scan(this, phrase, count,
                                    (done, total) -> {
                                        autoScanProgress = "پیشرفت اسکن: " + done + " از " + total;
                                        runOnUiThread(() -> renderAutoScanStatus());
                                    },
                                    () -> scanCancelled,
                                    line -> {
                                        synchronized (MainActivity.this) {
                                            autoScanHits = "🎉 موجودی پیدا شد!\n" + line + "\n\n" + autoScanHits;
                                        }
                                        runOnUiThread(() -> renderAutoScanStatus());
                                    });
                            } catch (Exception | LinkageError e) {
                                response = "اسکن متوقف شد: " + e.getClass().getSimpleName();
                            }
                            final String out = response;
                            runOnUiThread(() -> {
                                result.setText(out);
                                autoScanButton.setEnabled(true);
                                cancelScanButton.setEnabled(false);
                            });
                        });
                    })
                    .setNegativeButton("انصراف", null)
                    .show();
            })
            .setNegativeButton("انصراف", null)
            .show();
    }

    private void renderAutoScanStatus() {
        String hits = autoScanHits;
        String status = autoScanProgress;
        result.setText((hits == null || hits.isEmpty() ? "" : hits + "\n--------------------\n\n")
                + (status == null ? "" : status));
    }

    private void showRecoveryDialog() {
        EditText input = new EditText(this);
        input.setHint("عبارت خودت را وارد کن؛ جای کلمه گمشده ? بگذار");
        input.setSingleLine(false);

        new AlertDialog.Builder(this)
            .setTitle("بازیابی کیف پول")
            .setMessage("فقط عبارت بازیابی متعلق به خودت را وارد کن.")
            .setView(input)
            .setPositiveButton("بررسی", (dialog, which) -> {
                String phrase = input.getText().toString().trim();

                executor.execute(() -> {
                    try {
                        java.util.List<String> candidates =
                            SafeMnemonicRecovery.findCandidates(this, phrase);

                        runOnUiThread(() -> {
                            String message = candidates.isEmpty()
                                ? "کاندیدای معتبر پیدا نشد."
                                : "تعداد عبارت‌های معتبر: " + candidates.size()
                                  + "\n\n" + android.text.TextUtils.join("\n\n", candidates);

                            new AlertDialog.Builder(this)
                                .setTitle("نتیجه بررسی")
                                .setMessage(message)
                                .setPositiveButton("بستن", null)
                                .show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() ->
                            new AlertDialog.Builder(this)
                                .setTitle("خطا")
                                .setMessage("بررسی انجام نشد: " + e.getMessage())
                                .setPositiveButton("بستن", null)
                                .show()
                        );
                    }
                });
            })
            .setNegativeButton("انصراف", null)
            .show();
        
    }

    private void showGeneratePhraseDialog() {

        String[] choices = {"۱۲ کلمه‌ای", "۲۴ کلمه‌ای"};

        new AlertDialog.Builder(this)
            .setTitle("تولید عبارت بازیابی جدید")
            .setItems(choices, (dialog, which) -> {

                int count = (which == 0) ? 12 : 24;

                executor.execute(() -> {
                    try {
                        java.util.List<String> words =
                            MnemonicGenerator.generate(count);

                        String phrase =
                            android.text.TextUtils.join(" ", words);
                        
runOnUiThread(() -> {
    EditText phraseBox = new EditText(this);
    phraseBox.setText(phrase);
    phraseBox.setTextIsSelectable(true);
    phraseBox.setSingleLine(false);

    new AlertDialog.Builder(this)
        .setTitle("عبارت بازیابی جدید")
        .setMessage("عبارت را در جای امن و آفلاین نگهداری کن.")
        .setView(phraseBox)
        .setPositiveButton("کپی عبارت", (d, w) -> {
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);

            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "Recovery Phrase", phrase
                )
            );

            Toast.makeText(
                this, "عبارت کپی شد", Toast.LENGTH_SHORT
            ).show();
        })
        .setNegativeButton("بستن", null)
        .show();
});


                    } catch (Exception e) {
                        runOnUiThread(() ->
                            new AlertDialog.Builder(this)
                                .setTitle("خطا")
                                .setMessage(e.getMessage())
                                .setPositiveButton("بستن", null)
                                .show()
                        );
                    }
                });
            })
            .setNegativeButton("انصراف", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
