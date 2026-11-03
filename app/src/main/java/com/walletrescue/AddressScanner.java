package com.walletrescue;

import java.util.List;

public class AddressScanner {

    private static final int MAX_ADDRESSES = 20;

    public static String scan(
            MainActivity activity,
            List<String> addresses,
            int network
    ) throws Exception {

        if (addresses == null || addresses.isEmpty()) {
            throw new Exception("هیچ آدرسی وارد نشده است.");
        }

        if (addresses.size() > MAX_ADDRESSES) {
            throw new Exception("حداکثر ۲۰ آدرس در هر نوبت مجاز است.");
        }

        StringBuilder output = new StringBuilder();
        int checked = 0;

        for (String item : addresses) {

            String address = item == null ? "" : item.trim();

            if (address.isEmpty()) continue;

            if (!activity.validAddress(address, network)) {
                output.append("آدرس نامعتبر:\n")
                      .append(address)
                      .append("\n------------------\n");
                continue;
            }

            try {
                String result;

                if (network == 0) {
                    result = activity.checkBitcoin(address);
                } else if (network == 1) {
                    result = activity.checkEthereum(address);
                } else if (network == 2) {
                    result = activity.checkTron(address);
                } else {
                    throw new Exception("شبکه نامعتبر است.");
                }

                output.append(result)
                      .append("\n\n------------------\n");

            } catch (Exception e) {
                output.append(address)
                      .append("\nخطا: ")
                      .append(e.getMessage())
                      .append("\n------------------\n");
            }

            checked++;
        }

        output.append("\nتعداد آدرس‌های بررسی‌شده: ")
              .append(checked);

        return output.toString();
    }
}
