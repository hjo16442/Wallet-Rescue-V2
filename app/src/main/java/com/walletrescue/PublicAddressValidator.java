package com.walletrescue;

import org.bitcoinj.core.Address;
import org.bitcoinj.params.MainNetParams;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Read-only public-address validation.
 *
 * This class deliberately accepts public addresses only. It never accepts,
 * derives, stores, or exports mnemonic phrases or private keys.
 */
public final class PublicAddressValidator {
    public enum Network { BITCOIN, ETHEREUM, TRON }

    private static final Pattern ETH = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern TRON = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");

    private PublicAddressValidator() {}

    public static boolean isValid(String value, Network network) {
        if (value == null || network == null) return false;
        String address = value.trim();
        if (address.isEmpty()) return false;

        switch (network) {
            case BITCOIN:
                try {
                    Address.fromString(MainNetParams.get(), address);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            case ETHEREUM:
                return ETH.matcher(address).matches();
            case TRON:
                return TRON.matcher(address).matches();
            default:
                return false;
        }
    }

    public static String networkLabel(Network network) {
        if (network == null) return "نامشخص";
        switch (network) {
            case BITCOIN: return "Bitcoin Mainnet (BTC)";
            case ETHEREUM: return "Ethereum Mainnet (ETH)";
            case TRON: return "TRON Mainnet (TRX)";
            default: return network.name().toUpperCase(Locale.ROOT);
        }
    }
}
