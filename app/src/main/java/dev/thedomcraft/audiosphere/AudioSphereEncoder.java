package dev.thedomcraft.audiosphere;

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

/**
 * ASPH v4 encoder/decoder (WAV-only input).
 *
 * - Input: WAV files only (required).
 * - Output ASPH: PCM_SIGNED, little-endian, with sample rate / bit depth / channels
 *   mirroring the source WAV, clamped to:
 *      sampleRate <= 96 kHz
 *      bitsPerSample <= 24
 *      channels <= 2
 */

/**
 * This is a bold claim, but in the future i think about supporting up to 5.1 channels,
 *  no dolby but like spacial audio, but this is beyond my knowlade right now, so yeah
 */

public final class AudioSphereEncoder {

    private AudioSphereEncoder() {}

    private static final int MAX_SAMPLE_RATE = 96_000;
    private static final int MIN_SAMPLE_RATE = 8_000;
    private static final int MAX_BITS_PER_SAMPLE = 24;
    private static final int MIN_BITS_PER_SAMPLE = 8;
    private static final int MAX_CHANNELS = 2;
    private static final int MIN_CHANNELS = 1;

    private static final byte[] ENCRYPTION_KEY = new byte[]{
            0x21, 0x43, 0x65, (byte) 0x87, 0x09, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE,
            0x13, 0x57, (byte) 0x9B, (byte) 0xDF, 0x02, 0x46, (byte) 0x8A, (byte) 0xCE
    };

    private static final byte[] INIT_VECTOR = new byte[]{
            0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88
    };

    private static final String MAGIC = "ASPH";
    private static final byte VERSION = 0x04; // ASPH v4

    /**
     * Encode a WAV file into ASPH v4.
     * The output format mirrors the input WAV format (clamped to <= 96kHz, 24-bit, 2ch).
     */
    public static void encodeToAudioSphere(String inputFile, String outputFile) {
        try {
            EncodedPcmResult pcm = readAndConvertWavToPcmMirroringInput(inputFile);

            byte[] combinedData;
            try (var combinedStream = new ByteArrayOutputStream()) {
                // ASPH magic + version
                combinedStream.write(MAGIC.getBytes());
                combinedStream.write(VERSION);

                // Write audio format details (little endian)
                combinedStream.write(intToLittleEndian(Math.round(pcm.sampleRate())));
                combinedStream.write(intToLittleEndian(pcm.bitsPerSample()));
                combinedStream.write(intToLittleEndian(pcm.channels()));

                // PCM audio data
                combinedStream.write(pcm.pcmBytes());

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

            // Write final file: magic (unencrypted) + length + encrypted payload
            try (var fos = new FileOutputStream(outputFile)) {
                fos.write(MAGIC.getBytes());
                fos.write(intToLittleEndian(encrypted.length));
                fos.write(encrypted);
            }

            long originalSize = new File(inputFile).length();
            long encodedSize = new File(outputFile).length();
            double ratio = (double) encodedSize / Math.max(1, originalSize) * 100.0;

            System.out.println("[AudioSphere] ASPH Version: " + (VERSION & 0xFF));
            System.out.println("[AudioSphere] Audio Format (mirrored from input WAV):");
            System.out.println("[AudioSphere] - Sample Rate: " + Math.round(pcm.sampleRate()) + "Hz");
            System.out.println("[AudioSphere] - Bit Depth: " + pcm.bitsPerSample() + "-bit");
            System.out.println("[AudioSphere] - Channels: " + pcm.channels());
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
     * Decode ASPH v4 back into WAV.
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

    // --------- WAV/PCM conversion helpers ---------

    private record EncodedPcmResult(byte[] pcmBytes, float sampleRate, int bitsPerSample, int channels) {}

    /**
     * Read a WAV file and convert it to PCM_SIGNED little-endian with format
     * mirroring the source (clamped to supported max values).
     *
     * Input MUST be a WAV file.
     */
    private static EncodedPcmResult readAndConvertWavToPcmMirroringInput(String inputFile) throws Exception {
        File inFile = new File(inputFile);

        try (AudioInputStream originalStream = AudioSystem.getAudioInputStream(inFile)) {
            AudioFileFormat.Type fileType = AudioSystem.getAudioFileFormat(inFile).getType();
            if (!AudioFileFormat.Type.WAVE.equals(fileType)) {
                throw new IOException("Input must be a WAV file. Detected type: " + fileType);
            }

            AudioFormat base = originalStream.getFormat();

            float srcRate = base.getSampleRate();
            int srcBits = base.getSampleSizeInBits();
            int srcChannels = base.getChannels();

            if (srcRate <= 0) {
                throw new IOException("Unsupported WAV: invalid sample rate " + srcRate);
            }
            if (srcChannels <= 0) {
                throw new IOException("Unsupported WAV: invalid channel count " + srcChannels);
            }

            // Some WAVs may report -1 or 0 for bits; handle that
            if (srcBits <= 0) {
                // guess 16 as a safe default
                srcBits = 16;
            }

            // Clamp to your desired capabilities
            float targetRate = clamp(srcRate, MIN_SAMPLE_RATE, MAX_SAMPLE_RATE);
            int targetBits = clamp(srcBits, MIN_BITS_PER_SAMPLE, MAX_BITS_PER_SAMPLE);
            // force targetBits to 8/16/24 – round to nearest multiple of 8
            targetBits = (targetBits + 4) / 8 * 8;
            if (targetBits > MAX_BITS_PER_SAMPLE) targetBits = MAX_BITS_PER_SAMPLE;

            int targetChannels = clamp(srcChannels, MIN_CHANNELS, MAX_CHANNELS);

            int frameSize = targetChannels * (targetBits / 8);

            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    targetRate,
                    targetBits,
                    targetChannels,
                    frameSize,
                    targetRate,
                    false // little endian
            );

            AudioInputStream convertedStream = AudioSystem.getAudioInputStream(targetFormat, originalStream);

            try (convertedStream; var baos = new ByteArrayOutputStream()) {
                convertedStream.transferTo(baos);
                byte[] pcm = baos.toByteArray();
                return new EncodedPcmResult(pcm, targetRate, targetBits, targetChannels);
            }
        }
    }

    // --------- Little-endian helpers & clamps ---------

    private static byte[] intToLittleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static int littleEndianToInt(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
