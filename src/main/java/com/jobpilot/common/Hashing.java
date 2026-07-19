package com.jobpilot.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {
    }

    public static String sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
