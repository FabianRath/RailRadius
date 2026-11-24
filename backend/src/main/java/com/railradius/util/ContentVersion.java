package com.railradius.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * A version named by content: the first 8 bytes of the SHA-256 in hex, so equal bytes always get the same version and
 * changed bytes a new one. Names the browser data file (#9) and the browser engine (#12).
 */
public final class ContentVersion {

    private static final String HASH_ALGORITHM = "SHA-256";
    // 64 bits: a version names one of a handful of files, it doesn't have to resist forgery
    private static final int VERSION_BYTES = 8;

    private ContentVersion() {
    }

    /** A digest to hash content with as it streams past; {@link #of(MessageDigest)} then names it. */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has " + HASH_ALGORITHM, e);
        }
    }

    public static String of(MessageDigest digest) {
        return HexFormat.of().formatHex(Arrays.copyOf(digest.digest(), VERSION_BYTES));
    }

    public static String of(byte[] content) {
        MessageDigest digest = digest();
        digest.update(content);
        return of(digest);
    }
}
