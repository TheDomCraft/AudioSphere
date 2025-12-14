package dev.thedomcraft.audiosphere;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AudioSpherePlayer {

    private AudioSpherePlayer() {}

    public static void playAudioSphere(String inputFile, boolean loop) {
        try {
            Map<String, String> metadata = MetadataHandler.readMetadata(inputFile);
            byte[] combined = AudioSphereEncoder.decryptAndDecompress(inputFile);

            int offset = 0;
            byte[] magic = Arrays.copyOfRange(combined, offset, offset + 4);
            offset += 4;
            if (!Arrays.equals(magic, "ASPH".getBytes())) {
                throw new IOException("Invalid ASPH data.");
            }

            int version = combined[offset] & 0xFF;
            offset += 1;

            int sampleRate = littleEndianToInt(combined, offset);
            int bitsPerSample = littleEndianToInt(combined, offset + 4);
            int channels = littleEndianToInt(combined, offset + 8);
            offset += 12;

            byte[] audioData = Arrays.copyOfRange(combined, offset, combined.length);

            AudioFormat format = new AudioFormat(
                    sampleRate,
                    bitsPerSample,
                    channels,
                    true,
                    false
            );

            int frameSize = format.getFrameSize();
            float bytesPerSecond = format.getFrameRate() * frameSize;

            String title = metadata.getOrDefault("Title", inputFile);
            String artist = metadata.getOrDefault("Artist", "Unknown");
            String album = metadata.getOrDefault("Album", "Unknown");

            System.out.println("[AudioSphere] Now Playing: " + title);
            System.out.println("[AudioSphere] Artist: " + artist);
            System.out.println("[AudioSphere] Album: " + album);

            AtomicBoolean stopPlayback = new AtomicBoolean(false);
            AtomicBoolean paused = new AtomicBoolean(false);
            AtomicReference<Float> volumeRef = new AtomicReference<>(1.0f);
            AtomicReference<Integer> positionRef = new AtomicReference<>(0); // byte index in audioData

            // Key listener on another thread
            Thread keyThread = new Thread(() -> keyListener(loop, paused, stopPlayback, volumeRef, positionRef, bytesPerSecond, audioData.length));
            keyThread.setDaemon(true);
            keyThread.start();

            long totalMillis = Math.round((audioData.length / bytesPerSecond) * 1000);
            long startTime = System.currentTimeMillis();

            while (!stopPlayback.get()) {
                int startPos = positionRef.get();
                if (startPos < 0) startPos = 0;
                if (startPos >= audioData.length) {
                    if (loop) {
                        startPos = 0;
                    } else {
                        break;
                    }
                }

                positionRef.set(startPos);

                try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                        new DataLine.Info(SourceDataLine.class, format))) {
                    line.open(format);
                    line.start();
                    line.flush();
                    FloatControl volumeControl = null;
                    if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    }

                    byte[] buffer = new byte[4096];
                    while (!stopPlayback.get()) {
                        if (paused.get()) {
                            // Sleep briefly while paused
                            Thread.sleep(50);
                            continue;
                        }

                        int pos = positionRef.get();
                        if (pos >= audioData.length) {
                            if (loop) {
                                positionRef.set(0);
                                startTime = System.currentTimeMillis();
                                continue;
                            } else {
                                break;
                            }
                        }

                        int remaining = audioData.length - pos;
                        int toCopy = Math.min(buffer.length, remaining);
                        System.arraycopy(audioData, pos, buffer, 0, toCopy);
                        int written = line.write(buffer, 0, toCopy);
                        positionRef.set(pos + written);

                        // Volume
                        if (volumeControl != null) {
                            float requested = volumeRef.get();
                            requested = Math.max(0.0f, Math.min(1.0f, requested));
                            // Map 0.0..1.0 to gain dB range
                            float min = volumeControl.getMinimum();
                            float max = volumeControl.getMaximum();
                            float dB = min + (max - min) * requested;
                            volumeControl.setValue(dB);
                        }

                        // Progress display every ~500ms
                        long now = System.currentTimeMillis();
                        long elapsedMillis = now - startTime;
                        long remainingMillis = Math.max(0, totalMillis - elapsedMillis);
                        printProgress(positionRef.get(), audioData.length, elapsedMillis, remainingMillis, loop);
                    }
                    line.drain();
                }
            }

            System.out.println("\n[AudioSphere] Playback ended.");

        } catch (Exception ex) {
            System.out.println("[AudioSphere] Error playing file: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // ---------- controls & helpers ----------

    private static void keyListener(
            boolean loop,
            AtomicBoolean paused,
            AtomicBoolean stopPlayback,
            AtomicReference<Float> volumeRef,
            AtomicReference<Integer> positionRef,
            float bytesPerSecond,
            int totalBytes
    ) {
        try {
            // Simple stdin-based controls – one character per command
            while (!stopPlayback.get()) {
                int ch = System.in.read();
                if (ch == -1) break;
                char c = (char) ch;
                switch (c) {
                    case 'p', 'P' -> {
                        boolean newState = !paused.get();
                        paused.set(newState);
                        System.out.println(newState ? "\n[AudioSphere] Paused" : "\n[AudioSphere] Resumed");
                    }
                    case '+', '=' -> {
                        float v = volumeRef.get();
                        v = Math.min(1.0f, v + 0.1f);
                        volumeRef.set(v);
                        System.out.printf("%n[AudioSphere] Volume: %.0f%%%n", v * 100);
                    }
                    case '-' -> {
                        float v = volumeRef.get();
                        v = Math.max(0.0f, v - 0.1f);
                        volumeRef.set(v);
                        System.out.printf("%n[AudioSphere] Volume: %.0f%%%n", v * 100);
                    }
                    case 'f', 'F' -> {
                        int delta = (int) (bytesPerSecond * 10); // 10 seconds
                        int newPos = Math.min(totalBytes, positionRef.get() + delta);
                        positionRef.set(newPos);
                        System.out.println("\n[AudioSphere] Seek forward 10s");
                    }
                    case 'b', 'B' -> {
                        int delta = (int) (bytesPerSecond * 10);
                        int newPos = Math.max(0, positionRef.get() - delta);
                        positionRef.set(newPos);
                        System.out.println("\n[AudioSphere] Seek backward 10s");
                    }
                    case 'q', 'Q' -> {
                        stopPlayback.set(true);
                        System.out.println("\n[AudioSphere] Stopping playback.");
                    }
                    default -> {
                        // ignore other keys
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void printProgress(int position, int totalBytes, long elapsedMillis, long remainingMillis, boolean loop) {
        int totalBlocks = 50;
        double progress = (double) position / Math.max(1, totalBytes);
        int filled = (int) (progress * totalBlocks);

        String bar = "[" +
                "#".repeat(Math.max(0, filled)) +
                "-".repeat(Math.max(0, totalBlocks - filled)) +
                "]";
        String elapsedStr = formatTime(elapsedMillis);
        String remainingStr = formatTime(remainingMillis);

        System.out.print("\r" + bar + String.format(" %3.0f%% | Elapsed: %s | Remaining: %s | Loop: %s",
                progress * 100,
                elapsedStr,
                remainingStr,
                loop ? "On" : "Off"
        ));
        System.out.flush();
    }

    private static String formatTime(long millis) {
        long sec = millis / 1000;
        long m = sec / 60;
        long s = sec % 60;
        return "%02d:%02d".formatted(m, s);
    }

    private static int littleEndianToInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}