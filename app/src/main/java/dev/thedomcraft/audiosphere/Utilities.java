package dev.thedomcraft.audiosphere;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * General AES + GZip helpers.
 * Not strictly required by the current encoder (it does inline work),
 * but kept for parity and possible reuse.
 */
public final class Utilities {

    private Utilities() {}

    private static final byte[] ENCRYPTION_KEY = new byte[]{
            0x21, 0x43, 0x65, (byte) 0x87, 0x09, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE,
            0x13, 0x57, (byte) 0x9B, (byte) 0xDF, 0x02, 0x46, (byte) 0x8A, (byte) 0xCE
    };
    private static final byte[] INIT_VECTOR = new byte[]{
            0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88
    };

    public static byte[] encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(ENCRYPTION_KEY, "AES"),
                new IvParameterSpec(INIT_VECTOR)
        );
        return cipher.doFinal(data);
    }

    public static byte[] decrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(ENCRYPTION_KEY, "AES"),
                new IvParameterSpec(INIT_VECTOR)
        );
        return cipher.doFinal(data);
    }

    public static byte[] compress(byte[] data) throws IOException {
        try (var baos = new ByteArrayOutputStream();
             var gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
            gzip.finish();
            return baos.toByteArray();
        }
    }

    public static byte[] decompress(byte[] data) throws IOException {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data));
             var baos = new ByteArrayOutputStream()) {
            gzip.transferTo(baos);
            return baos.toByteArray();
        }
    }
}
