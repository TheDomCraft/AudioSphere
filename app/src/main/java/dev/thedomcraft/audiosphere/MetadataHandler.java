package dev.thedomcraft.audiosphere;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public final class MetadataHandler {

    private MetadataHandler() {}

    private static final int METADATA_SIZE = 512; // bytes at end of file

    public static void addMetadata(String inputFile, String title, String artist, String album) {
        try {
            byte[] fileBytes = readAllBytes(new File(inputFile));

            try (var memoryStream = new ByteArrayOutputStream()) {
                memoryStream.write(fileBytes);

                byte[] titleBytes = title.getBytes("UTF-8");
                byte[] artistBytes = artist.getBytes("UTF-8");
                byte[] albumBytes = album.getBytes("UTF-8");

                int totalMetadataLength =
                        4 + titleBytes.length +
                        4 + artistBytes.length +
                        4 + albumBytes.length;

                if (totalMetadataLength > METADATA_SIZE) {
                    throw new IllegalArgumentException("Metadata size exceeds reserved space.");
                }

                memoryStream.write(intLE(titleBytes.length));
                memoryStream.write(titleBytes);

                memoryStream.write(intLE(artistBytes.length));
                memoryStream.write(artistBytes);

                memoryStream.write(intLE(albumBytes.length));
                memoryStream.write(albumBytes);

                int remaining = METADATA_SIZE - totalMetadataLength;
                if (remaining > 0) {
                    memoryStream.write(new byte[remaining]);
                }

                try (var fos = new FileOutputStream(inputFile)) {
                    memoryStream.writeTo(fos);
                }
            }

            System.out.println("[AudioSphere] Metadata successfully added to " + inputFile);
        } catch (Exception ex) {
            System.out.println("[AudioSphere] Error adding metadata: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public static Map<String, String> readMetadata(String inputFile) {
        Map<String, String> metadata = new HashMap<>();
        try {
            byte[] fileBytes = readAllBytes(new File(inputFile));
            if (fileBytes.length < METADATA_SIZE) {
                return metadata; // no metadata
            }

            int metaStart = fileBytes.length - METADATA_SIZE;
            try (var in = new ByteArrayInputStream(fileBytes, metaStart, METADATA_SIZE)) {
                byte[] lenBuf = in.readNBytes(4);
                if (lenBuf.length < 4) return metadata;
                int titleLen = fromIntLE(lenBuf);
                if (titleLen > 0 && titleLen <= in.available()) {
                    byte[] titleBytes = in.readNBytes(titleLen);
                    metadata.put("Title", new String(titleBytes, "UTF-8"));
                }

                lenBuf = in.readNBytes(4);
                if (lenBuf.length < 4) return metadata;
                int artistLen = fromIntLE(lenBuf);
                if (artistLen > 0 && artistLen <= in.available()) {
                    byte[] artistBytes = in.readNBytes(artistLen);
                    metadata.put("Artist", new String(artistBytes, "UTF-8"));
                }

                lenBuf = in.readNBytes(4);
                if (lenBuf.length < 4) return metadata;
                int albumLen = fromIntLE(lenBuf);
                if (albumLen > 0 && albumLen <= in.available()) {
                    byte[] albumBytes = in.readNBytes(albumLen);
                    metadata.put("Album", new String(albumBytes, "UTF-8"));
                }
            }
        } catch (Exception ex) {
            System.out.println("[AudioSphere] Error reading metadata: " + ex.getMessage());
        }

        return metadata;
    }

    private static byte[] readAllBytes(File f) throws IOException {
        try (var in = new FileInputStream(f);
             var baos = new ByteArrayOutputStream()) {
            in.transferTo(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] intLE(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static int fromIntLE(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
