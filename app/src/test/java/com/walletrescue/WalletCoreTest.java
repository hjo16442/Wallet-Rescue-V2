package com.walletrescue;

import org.junit.Test;
import org.bitcoinj.crypto.MnemonicCode;
import static org.junit.Assert.*;

public class WalletCoreTest {
    @Test
    public void createsValidTwelveWordBitcoinWallet() throws Exception {
        WalletCore.WalletResult wallet = WalletCore.create(12);
        assertEquals(12, wallet.words.size());
        MnemonicCode.INSTANCE.check(wallet.words);
        assertNotNull(wallet.mnemonic());
        assertEquals(12, wallet.mnemonic().trim().split("\\s+").length);
        assertTrue(wallet.btc.matches("^1[1-9A-HJ-NP-Za-km-z]{25,34}$"));
        assertTrue(AddressScanner.validAddress(wallet.btc, AddressScanner.BTC));
    }

    @Test
    public void createsValidTwentyFourWordBitcoinWallet() throws Exception {
        WalletCore.WalletResult wallet = WalletCore.create(24);
        assertEquals(24, wallet.words.size());
        MnemonicCode.INSTANCE.check(wallet.words);
        assertEquals(24, wallet.mnemonic().trim().split("\\s+").length);
        assertTrue(wallet.btc.matches("^1[1-9A-HJ-NP-Za-km-z]{25,34}$"));
        assertTrue(AddressScanner.validAddress(wallet.btc, AddressScanner.BTC));
    }

    @Test
    public void consecutiveWalletsDoNotReuseBitcoinAddresses() throws Exception {
        WalletCore.WalletResult first = WalletCore.create(12);
        WalletCore.WalletResult second = WalletCore.create(12);
        assertNotEquals(first.btc, second.btc);
    }

    @Test
    public void validatesBitcoinChecksumNotJustAddressShape() {
        assertTrue(AddressScanner.validAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", AddressScanner.BTC));
        assertFalse(AddressScanner.validAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyU", AddressScanner.BTC));
        assertFalse(AddressScanner.validAddress("not-a-bitcoin-address", AddressScanner.BTC));
    }

    @Test
    public void rejectsNullAndUnknownNetworkAddresses() {
        assertFalse(AddressScanner.validAddress(null, AddressScanner.BTC));
        assertFalse(AddressScanner.validAddress("", AddressScanner.BTC));
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
