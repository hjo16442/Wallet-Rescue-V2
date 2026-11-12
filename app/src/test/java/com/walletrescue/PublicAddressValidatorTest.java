package com.walletrescue;

import org.junit.Test;
import static org.junit.Assert.*;

public class PublicAddressValidatorTest {
    @Test public void acceptsBitcoinMainnetAddress() {
        assertTrue(PublicAddressValidator.isValid(
            "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            PublicAddressValidator.Network.BITCOIN));
    }

    @Test public void rejectsInvalidBitcoinAddress() {
        assertFalse(PublicAddressValidator.isValid(
            "not-a-bitcoin-address",
            PublicAddressValidator.Network.BITCOIN));
    }

    @Test public void acceptsEthereumAddressShape() {
        assertTrue(PublicAddressValidator.isValid(
            "0x0000000000000000000000000000000000000001",
            PublicAddressValidator.Network.ETHEREUM));
    }

    @Test public void rejectsEthereumWrongLength() {
        assertFalse(PublicAddressValidator.isValid(
            "0x1234", PublicAddressValidator.Network.ETHEREUM));
    }

    @Test public void acceptsTronAddressShape() {
        assertTrue(PublicAddressValidator.isValid(
            "TJRabPrwbZy45sbavfcN9g7pF9Jw2w6J4b",
            PublicAddressValidator.Network.TRON));
    }

    @Test public void rejectsTronWrongPrefix() {
        assertFalse(PublicAddressValidator.isValid(
            "XJRabPrwbZy45sbavfcN9g7pF9Jw2w6J4b",
            PublicAddressValidator.Network.TRON));
    }
}
