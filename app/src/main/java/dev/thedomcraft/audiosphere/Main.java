package dev.thedomcraft.audiosphere;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String command = args[0].toLowerCase();
        String inputFile = args[1];

        switch (command) {
            case "encode" -> {
                if (args.length < 3) {
                    System.out.println("[AudioSphere] Error: Output file not specified for encoding.");
                    printUsage();
                    return;
                }
                String outputFile = args[2];
                AudioSphereEncoder.encodeToAudioSphere(inputFile, outputFile);
            }
            case "decode" -> {
                if (args.length < 3) {
                    System.out.println("[AudioSphere] Error: Output file not specified for decoding.");
                    printUsage();
                    return;
                }
                String outputFile = args[2];
                AudioSphereEncoder.decodeFromAudioSphere(inputFile, outputFile);
            }
            case "play" -> {
                boolean loop = args.length > 2 && args[2].equalsIgnoreCase("loop");
                AudioSpherePlayer.playAudioSphere(inputFile, loop);
            }
            case "metadata" -> {
                if (args.length < 4) {
                    System.out.println("[AudioSphere] Error: Insufficient metadata parameters.");
                    printUsage();
                    return;
                }
                String title = args[2];
                String artist = args[3];
                String album = args.length > 4 ? args[4] : "";
                MetadataHandler.addMetadata(inputFile, title, artist, album);
            }
            case "version" -> {
                System.out.println("[AudioSphere] AudioSphere Version 4.0.0.0");
                System.out.println("[AudioSphere] Build Version 14122025");
            }
            default -> {
                System.out.printf("[AudioSphere] Error: Unknown command '%s'%n", command);
                printUsage();
            }
        }
    }

    private static void printUsage() {
        System.out.println("================================================================");
        System.out.println(" AudioSphere         |           Copyright (C) 2025 TheDomCraft ");
        System.out.println("================================================================");
        System.out.println(" Usage:");
        System.out.println("   Encode : audiosphere encode <input.wav> <output.asph>");
        System.out.println("   Decode : audiosphere decode <input.asph> <output.wav>");
        System.out.println("   Play   : audiosphere play <input.asph> [loop]");
        System.out.println("   Metadata : audiosphere metadata <input.asph> <title> <artist> [album]");
        System.out.println("================================================================");
        System.out.println(" During playback:");
        System.out.println("   p      : pause / resume");
        System.out.println("   + / -  : volume up / down");
        System.out.println("   f / b  : seek forward / backward 10 seconds");
        System.out.println("   q      : stop playback");
        System.out.println("================================================================");
    }
}
