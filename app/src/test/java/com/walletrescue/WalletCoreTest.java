package com.walletrescue;

import org.junit.Test;
import org.bitcoinj.crypto.MnemonicCode;
import static org.junit.Assert.*;

public class WalletCoreTest {
    @Test
    public void createsValidTwelveWordWallet() throws Exception {
        WalletCore.WalletResult wallet = WalletCore.create(12);
        assertEquals(12, wallet.words.size());
        MnemonicCode.INSTANCE.check(wallet.words);
        assertNotNull(wallet.mnemonic());
        assertTrue(wallet.mnemonic().trim().split("\\s+").length == 12);
        assertTrue(wallet.btc.matches("^1[1-9A-HJ-NP-Za-km-z]{25,34}$"));
        assertTrue(wallet.eth.matches("^0x[0-9a-fA-F]{40}$"));
        assertTrue(wallet.tron.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$"));
        assertTrue(AddressScanner.validAddress(wallet.btc, AddressScanner.BTC));
        assertTrue(AddressScanner.validAddress(wallet.eth, AddressScanner.ETH));
        assertTrue(AddressScanner.validAddress(wallet.tron, AddressScanner.TRON));
    }

    @Test
    public void createsValidTwentyFourWordWallet() throws Exception {
        WalletCore.WalletResult wallet = WalletCore.create(24);
        assertEquals(24, wallet.words.size());
        MnemonicCode.INSTANCE.check(wallet.words);
        assertEquals(24, wallet.mnemonic().trim().split("\\s+").length);
        assertTrue(wallet.btc.matches("^1[1-9A-HJ-NP-Za-km-z]{25,34}$"));
        assertTrue(wallet.eth.matches("^0x[0-9a-fA-F]{40}$"));
        assertTrue(wallet.tron.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$"));
        assertTrue(AddressScanner.validAddress(wallet.btc, AddressScanner.BTC));
        assertTrue(AddressScanner.validAddress(wallet.eth, AddressScanner.ETH));
        assertTrue(AddressScanner.validAddress(wallet.tron, AddressScanner.TRON));
    }

    @Test
    public void consecutiveWalletsDoNotReuseAddresses() throws Exception {
        WalletCore.WalletResult first = WalletCore.create(12);
        WalletCore.WalletResult second = WalletCore.create(12);
        assertNotEquals(first.btc, second.btc);
        assertNotEquals(first.eth, second.eth);
        assertNotEquals(first.tron, second.tron);
    }

    @Test
    public void validatesBitcoinChecksumNotJustAddressShape() {
        assertTrue(AddressScanner.validAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", AddressScanner.BTC));
        assertFalse(AddressScanner.validAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyU", AddressScanner.BTC));
        assertFalse(AddressScanner.validAddress("not-a-bitcoin-address", AddressScanner.BTC));
    }

    @Test
    public void validatesEthereumEip55MixedCaseChecksum() {
        assertTrue(AddressScanner.validAddress("0x0000000000000000000000000000000000000000", AddressScanner.ETH));
        assertTrue(AddressScanner.validAddress("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", AddressScanner.ETH));
        assertFalse(AddressScanner.validAddress("0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", AddressScanner.ETH));
        assertFalse(AddressScanner.validAddress("0x1234", AddressScanner.ETH));
    }

    @Test
    public void validatesTronChecksum() throws Exception {
        WalletCore.WalletResult wallet = WalletCore.create(12);
        assertTrue(AddressScanner.validAddress(wallet.tron, AddressScanner.TRON));
        char last = wallet.tron.charAt(wallet.tron.length() - 1);
        char replacement = last == '1' ? '2' : '1';
        String changed = wallet.tron.substring(0, wallet.tron.length() - 1) + replacement;
        assertFalse(AddressScanner.validAddress(changed, AddressScanner.TRON));
        assertFalse(AddressScanner.validAddress("not-a-tron-address", AddressScanner.TRON));
    }

    @Test
    public void rejectsNullAndUnknownNetworkAddresses() {
        assertFalse(AddressScanner.validAddress(null, AddressScanner.BTC));
        assertFalse(AddressScanner.validAddress("", AddressScanner.ETH));
        assertFalse(AddressScanner.validAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", 99));
    }

    @Test
    public void rejectsUnsupportedWordCount() {
        try {
            WalletCore.create(15);
            fail("Unsupported BIP39 word count must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("۱۲"));
        } catch (Exception unexpected) {
            fail("Unexpected exception: " + unexpected);
        }
    }
}
