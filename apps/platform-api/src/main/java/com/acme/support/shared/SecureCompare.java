package com.acme.support.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SecureCompare {

    private SecureCompare() {
    }

    /** Constant-time equality. Hashing first removes the length side channel. */
    public static boolean equal(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ha = sha256(a);
        byte[] hb = sha256(b);
        return MessageDigest.isEqual(ha, hb);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
