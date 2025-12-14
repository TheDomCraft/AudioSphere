package xyz.thedomcraft.audiosphere;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class AudioSphereEncoder {

    private AudioSphereEncoder() {}

    private static final int SAMPLE_RATE = 44100;   // CD-quality
    private static final int BITS_PER_SAMPLE = 16;  // 16-bit
    private static final int CHANNELS = 2;          // Stereo

    private static final byte[] ENCRYPTION_KEY = new byte[]{
            0x21, 0x43, 0x65, (byte) 0x87, 0x09, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE,
            0x13, 0x57, (byte) 0x9B, (byte) 0xDF, 0x02, 0x46, (byte) 0x8A, (byte) 0xCE
    };

    private static final byte[] INIT_VECTOR = new byte[]{
            0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88
    };

    private static final String MAGIC = "ASPH";
    private static final byte VERSION = 0x01;

    /**
     * Encode a WAV file into ASPH format:
     * - Convert to PCM_SIGNED 16-bit stereo 44100 Hz
     * - Write header (magic, version, format)
     * - GZip compress
     * - AES-CBC encrypt
     * - Write magic + length + encrypted payload
     */
    public static void encodeToAudioSphere(String inputFile, String outputFile) {
        try {
            byte[] audioData = readAndConvertToPcm(inputFile);

            byte[] combinedData;
            try (var combinedStream = new ByteArrayOutputStream()) {
                // Write ASPH magic + version
                combinedStream.write(MAGIC.getBytes());
                combinedStream.write(VERSION);

                // Write audio format details
                combinedStream.write(intToLittleEndian(SAMPLE_RATE));
                combinedStream.write(intToLittleEndian(BITS_PER_SAMPLE));
                combinedStream.write(intToLittleEndian(CHANNELS));

                // Write audio data
                combinedStream.write(audioData);

                combinedData = combinedStream.toByteArray();
            }

            // Compress
            byte[] compressed;
            try (var baos = new ByteArrayOutputStream();
                 var gzip = new GZIPOutputStream(baos)) {
                gzip.write(combinedData);
                gzip.finish();
                compressed = baos.toByteArray();
            }

            // Encrypt
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(ENCRYPTION_KEY, "AES"),
                    new IvParameterSpec(INIT_VECTOR)
            );
            byte[] encrypted = cipher.doFinal(compressed);

            // Write final file: magic (unencrypted) + length + encrypted data
            try (var fos = new FileOutputStream(outputFile)) {
                fos.write(MAGIC.getBytes());
                fos.write(intToLittleEndian(encrypted.length));
                fos.write(encrypted);
            }

            long originalSize = new File(inputFile).length();
            long encodedSize = new File(outputFile).length();
            double ratio = (double) encodedSize / Math.max(1, originalSize) * 100.0;

            System.out.println("[AudioSphere] Audio Format:");
            System.out.println("[AudioSphere] - Sample Rate: " + SAMPLE_RATE + "Hz");
            System.out.println("[AudioSphere] - Bit Depth: " + BITS_PER_SAMPLE + "-bit");
            System.out.println("[AudioSphere] - Channels: " + CHANNELS);
            System.out.println("[AudioSphere] File Statistics:");
            System.out.printf("[AudioSphere] - Original Size: %,d bytes%n", originalSize);
            System.out.printf("[AudioSphere] - Encoded Size: %,d bytes%n", encodedSize);
            System.out.printf("[AudioSphere] - Compression Ratio: %.1f%%%n", ratio);
        } catch (Exception ex) {
            System.out.println("[AudioSphere] Error encoding file: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Decrypts & decompresses an ASPH file and returns the raw combined bytes:
     * [magic(4)][version(1)][sampleRate(4)][bits(4)][channels(4)][audio...]
     */
    public static byte[] decryptAndDecompress(String inputFile) {
        try (var fis = new FileInputStream(inputFile)) {
            byte[] magic = fis.readNBytes(4);
            if (!Arrays.equals(magic, MAGIC.getBytes())) {
                throw new IOException("Invalid file format (magic mismatch).");
            }

            byte[] lenBytes = fis.readNBytes(4);
            if (lenBytes.length != 4) {
                throw new IOException("Unexpected end of file while reading encrypted length.");
            }
            int encryptedLength = littleEndianToInt(lenBytes);

            byte[] encrypted = fis.readNBytes(encryptedLength);
            if (encrypted.length != encryptedLength) {
                throw new IOException("Unexpected end of file while reading encrypted payload.");
            }

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(ENCRYPTION_KEY, "AES"),
                    new IvParameterSpec(INIT_VECTOR)
            );
            byte[] decrypted = cipher.doFinal(encrypted);

            // GZip decompress
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(decrypted));
                 var baos = new ByteArrayOutputStream()) {
                gzip.transferTo(baos);
                return baos.toByteArray();
            }
        } catch (Exception ex) {
            throw new RuntimeException("[AudioSphere] Error during decrypt/decompress: " + ex.getMessage(), ex);
        }
    }

    /**
     * Decode ASPH file back into WAV.
     */
    public static void decodeFromAudioSphere(String inputFile, String outputFile) {
        try {
            byte[] combined = decryptAndDecompress(inputFile);
            try (var in = new ByteArrayInputStream(combined)) {
                byte[] magic = in.readNBytes(4);
                if (!Arrays.equals(magic, MAGIC.getBytes())) {
                    throw new IOException("Invalid data: wrong ASPH magic.");
                }

                int version = in.read();
                if (version != (VERSION & 0xFF)) {
                    System.out.println("[AudioSphere] Warning: ASPH version mismatch (" + version + ")");
                }

                byte[] fmtBytes = in.readNBytes(12);
                if (fmtBytes.length != 12) {
                    throw new IOException("Incomplete ASPH header.");
                }

                int sampleRate = littleEndianToInt(Arrays.copyOfRange(fmtBytes, 0, 4));
                int bitsPerSample = littleEndianToInt(Arrays.copyOfRange(fmtBytes, 4, 8));
                int channels = littleEndianToInt(Arrays.copyOfRange(fmtBytes, 8, 12));

                byte[] audioData = in.readAllBytes();

                AudioFormat format = new AudioFormat(
                        sampleRate,
                        bitsPerSample,
                        channels,
                        true,
                        false
                );

                try (var audioStream = new AudioInputStream(
                        new ByteArrayInputStream(audioData),
                        format,
                        audioData.length / format.getFrameSize()
                )) {
                    AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, new File(outputFile));
                }

                System.out.println("[AudioSphere] Successfully decoded " + inputFile + " to " + outputFile);
            }
        } catch (Exception ex) {
            System.out.println("[AudioSphere] Error decoding file: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // --------- WAV conversion helpers ---------

    /**
     * Read a WAV file and convert it to 16-bit, stereo, 44100Hz PCM if needed.
     */
    private static byte[] readAndConvertToPcm(String inputFile) throws Exception {
        File inFile = new File(inputFile);
        try (AudioInputStream originalStream = AudioSystem.getAudioInputStream(inFile)) {
            AudioFormat baseFormat = originalStream.getFormat();

            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE,
                    BITS_PER_SAMPLE,
                    CHANNELS,
                    CHANNELS * (BITS_PER_SAMPLE / 8),
                    SAMPLE_RATE,
                    false
            );

            AudioInputStream convertedStream = AudioSystem.getAudioInputStream(targetFormat, originalStream);
            // Note: Java's AudioSystem will handle most cases; for exotic formats,
            // you might need external decoders.

            try (convertedStream) {
                return convertedStream.readAllBytes();
            }
        }
    }

    // --------- Little-endian helpers ---------

    private static byte[] intToLittleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static int littleEndianToInt(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}