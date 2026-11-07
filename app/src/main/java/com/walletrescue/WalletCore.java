package com.walletrescue;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ScriptType;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.util.encoders.Hex;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WalletCore {
    private WalletCore() {}
    public static final class WalletResult {
        public final List<String> words;
        public final String btc, eth, tron;
        public final String btcPath="m/44'/0'/0'/0/0";
        public final String ethPath="m/44'/60'/0'/0/0";
        public final String tronPath="m/44'/195'/0'/0/0";
        WalletResult(List<String>w,String b,String e,String t){words=w;btc=b;eth=e;tron=t;}
        public String mnemonic(){return String.join(" ",words);}
    }
    public static WalletResult create(int wordCount) throws Exception {
        if(wordCount!=12 && wordCount!=24) throw new IllegalArgumentException("فقط ۱۲ یا ۲۴ کلمه مجاز است.");
        byte[] entropy=new byte[wordCount==12?16:32];
        new SecureRandom().nextBytes(entropy);
        List<String> words=MnemonicCode.INSTANCE.toMnemonic(entropy);
        byte[] seed=MnemonicCode.toSeed(words,"");
        Arrays.fill(entropy,(byte)0);
        DeterministicKey master=HDKeyDerivation.createMasterPrivateKey(seed);
        Arrays.fill(seed,(byte)0);
        DeterministicKey btc=derive(master,44,true,0,true,0,true,0,false,0,false);
        DeterministicKey eth=derive(master,44,true,60,true,0,true,0,false,0,false);
        DeterministicKey tron=derive(master,44,true,195,true,0,true,0,false,0,false);
        NetworkParameters params=MainNetParams.get();
        String btcAddr=Address.fromKey(params,ECKey.fromPrivate(btc.getPrivKeyBytes(),true),ScriptType.P2PKH).toString();
        String ethAddr=evmAddress(eth.getPrivKeyBytes());
        String tronAddr=tronAddress(tron.getPrivKeyBytes());
        return new WalletResult(new ArrayList<>(words),btcAddr,ethAddr,tronAddr);
    }
    private static DeterministicKey derive(DeterministicKey k,int a,boolean ah,int b,boolean bh,int c,boolean ch,int d,boolean dh,int e,boolean eh)throws Exception{
        int[] n={a,b,c,d,e};boolean[] h={ah,bh,ch,dh,eh};
        for(int i=0;i<n.length;i++) k=HDKeyDerivation.deriveChildKey(k,new ChildNumber(n[i],h[i]));
        return k;
    }
    private static byte[] keccak(byte[] input){KeccakDigest d=new KeccakDigest(256);d.update(input,0,input.length);byte[] out=new byte[32];d.doFinal(out,0);return out;}
    private static String evmAddress(byte[] privateKey)throws Exception{
        ECKey k=ECKey.fromPrivate(new BigInteger(1,privateKey),false);
        byte[] pub=k.getPubKeyPoint().getEncoded(false);
        byte[] hash=keccak(Arrays.copyOfRange(pub,1,pub.length));
        return "0x"+Hex.toHexString(Arrays.copyOfRange(hash,12,32));
    }
    private static String tronAddress(byte[] privateKey)throws Exception{
        ECKey k=ECKey.fromPrivate(new BigInteger(1,privateKey),false);
        byte[] pub=k.getPubKeyPoint().getEncoded(false);
        byte[] hash=keccak(Arrays.copyOfRange(pub,1,pub.length));
        byte[] payload=new byte[21];payload[0]=0x41;System.arraycopy(hash,12,payload,1,20);
        byte[] first=org.bitcoinj.core.Utils.sha256hash(payload).getBytes();
        byte[] second=org.bitcoinj.core.Utils.sha256hash(first).getBytes();
        byte[] check=Arrays.copyOf(second,4);
        byte[] full=Arrays.copyOf(payload,25);System.arraycopy(check,0,full,21,4);
        return org.bitcoinj.core.Base58.encode(full);
    }
}