package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class HashFunctions {

    public static int MD5(Data data) {
        try {
            String dataString = data.toString();
            MessageDigest msgDst = MessageDigest.getInstance("MD5");
            byte[] msgArr = msgDst.digest(dataString.getBytes(StandardCharsets.UTF_8));
            BigInteger bi = new BigInteger(1, msgArr);
            String hshtxt = bi.toString(16);
            while (hshtxt.length() < 32)
            {
                hshtxt = "0" + hshtxt;
            }
            return hshtxt.hashCode();
        } catch (NoSuchAlgorithmException abc) {
            System.out.println("NoSuchAlgorithmException NoSuchAlgorithmException NoSuchAlgorithmException");
            return 0;
        }
    }

    public static int SHA(Data data) {
        try {
            String dataString = data.toString();
            MessageDigest msgDst = MessageDigest.getInstance("SHA-256");
            byte[] msgArr = msgDst.digest(dataString.getBytes(StandardCharsets.UTF_8));
            BigInteger no = new BigInteger(1, msgArr);
            StringBuilder hexStr = new StringBuilder(no.toString(16));
            while (hexStr.length() < 32)
            {
                hexStr.insert(0, '0');
            }
            return hexStr.toString().hashCode();
        } catch (NoSuchAlgorithmException abc) {
            System.out.println("NoSuchAlgorithmException NoSuchAlgorithmException NoSuchAlgorithmException");
            return 0;
        }
    }
}
