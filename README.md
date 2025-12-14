# AudioSphere

AudioSphere is a cross‑platform command‑line tool for working with a custom encrypted audio container format (`.asph`).

The project is implemented in **Java 21**, targets Linux, macOS, and Windows, and uses only standard Java APIs.  
It is **experimental**, not designed or reviewed for production use, security‑critical scenarios, or long‑term archival.

---

## Status & Scope

> **Experimental software – use at your own risk.**  
> The format, APIs, and behavior may change at any time.  
> Do not rely on this project for:
> - Strong content protection or DRM  
> - Long‑term stable file formats  
> - Critical audio workflows in production environments  

The intent of this project is to:
- Explore custom audio container design
- Demonstrate simple AES + GZip pipelines in Java
- Provide a small CLI for learning and experimentation

---

## Features

- **Encode** standard WAV files into a custom ASPH format:
  - Normalizes to **PCM 16‑bit stereo, 44.1 kHz**
  - Packs audio with a small header:
    - Magic: `ASPH`
    - Version: `0x01`
    - Format: sample rate, bit depth, channels
  - Compresses data with **GZip**
  - Encrypts with **AES‑128 CBC (PKCS#5/7 padding)** using a fixed key/IV

- **Decode** ASPH files back into standard WAV:
  - Verifies magic and version
  - Decrypts, decompresses, and reconstructs the WAV file from the embedded format info

- **Play** ASPH audio directly from the CLI:
  - Uses Java’s `javax.sound.sampled` APIs
  - Works on Linux, macOS, and Windows (audio devices required)
  - Interactive playback controls:
    - `p` — Pause / Resume
    - `+` — Volume up
    - `-` — Volume down
    - `f` — Seek forward 10 seconds
    - `b` — Seek backward 10 seconds
    - `q` — Quit / stop playback
  - Optional loop mode

- **Metadata handling**:
  - Stores a fixed **512‑byte metadata block at the end of the ASPH file**
  - Supports:
    - Title
    - Artist
    - Album
  - Metadata is plain UTF‑8 (not encrypted) and appended after the encoded ASPH payload

---

## Project Structure

```text
audiosphere-java/
├── build.gradle          # Gradle build script (Groovy DSL)
├── settings.gradle       # Gradle settings
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── craftmusic/
        │           └── audiosphere/
        │               ├── Main.java              # CLI entrypoint
        │               ├── AudioSphereEncoder.java# Encode/Decode + AES+GZip
        │               ├── AudioSpherePlayer.java # CLI player with controls
        │               ├── MetadataHandler.java   # Read/write 512‑byte metadata block
        │               └── Utilities.java         # AES+GZip helpers
        └── resources/
```

---

## Requirements

- **Java 21** (JDK 21 or newer)
- The included **Gradle wrapper** (no separate Gradle install required)
- An audio output device (for playback)

---

## Building

From the repository root:

```bash
./gradlew clean build
```

This produces a runnable JAR in:

```text
build/libs/
  audiosphere-java-<version>.jar
```

Run it with:

```bash
java -jar build/libs/audiosphere-java-3.0.0.jar <command> [args...]
```

The project uses only standard JDK APIs, so the JAR runs anywhere Java 21 is available.

---

## Usage

General form:

```bash
java -jar audiosphere-java-3.0.0.jar <command> [args...]
```

Commands are intentionally simple and CLI‑oriented.

### 1. Encode

Encode a WAV file into an ASPH file:

```bash
java -jar audiosphere-java-3.0.0.jar encode <input.wav> <output.asph>
```

- Input should be a WAV file.
- Internally, audio is converted (if necessary) to:
  - 44.1 kHz
  - 16‑bit samples
  - 2 channels (stereo)
- The output `.asph` file contains:
  - `ASPH` magic
  - Version byte
  - Format block (sample rate, bit depth, channels)
  - Raw PCM audio
  - GZip compression
  - AES‑CBC encryption
  - (Optionally) a trailing 512‑byte metadata block

Example:

```bash
java -jar audiosphere-java-3.0.0.jar encode song.wav song.asph
```

### 2. Decode

Decode an ASPH file back into WAV:

```bash
java -jar audiosphere-java-3.0.0.jar decode <input.asph> <output.wav>
```

Example:

```bash
java -jar audiosphere-java-3.0.0.jar decode song.asph song_decoded.wav
```

The decoded file is standard WAV using the format stored in the ASPH header.

### 3. Play

Play an ASPH file on your system’s audio device:

```bash
java -jar audiosphere-java-3.0.0.jar play <input.asph> [loop]
```

- If the last argument is `loop` (case‑insensitive), playback restarts automatically at the end.

Examples:

```bash
# Play once
java -jar audiosphere-java-3.0.0.jar play song.asph

# Play in loop
java -jar audiosphere-java-3.0.0.jar play song.asph loop
```

**Interactive controls during playback (stdin):**

- `p` — Pause / Resume
- `+` — Volume up (clamped between 0–100%)
- `-` — Volume down
- `f` — Seek forward 10 seconds
- `b` — Seek backward 10 seconds
- `q` — Stop playback and exit

You can type these characters into the terminal while audio is playing.

> Note: Because this uses standard input, behavior may differ in IDE consoles.  
> It works best in a normal terminal (e.g. bash, zsh, PowerShell).

### 4. Metadata

Attach or update metadata on an ASPH file:

```bash
java -jar audiosphere-java-3.0.0.jar metadata <input.asph> <title> <artist> [album]
```

Example:

```bash
java -jar audiosphere-java-3.0.0.jar metadata song.asph "My Title" "My Artist" "My Album"
```

Internally, this writes a fixed 512‑byte metadata block at the end of the file:

- `int32` little‑endian: title length
- `title` bytes (UTF‑8)
- `int32` little‑endian: artist length
- `artist` bytes (UTF‑8)
- `int32` little‑endian: album length
- `album` bytes (UTF‑8)
- Remaining space in the 512‑byte block is zero‑filled

Metadata is automatically read during playback to display “Now Playing” information.

### 5. Version

Print build/version info:

```bash
java -jar audiosphere-java-3.0.0.jar version
```

---

## Security & Design Notes

- **Encryption**: AES‑128 in CBC mode with a fixed key and IV.
  - This is **not** a secure DRM system or robust content protection scheme.
  - It is sufficient for experimenting with encryption pipelines, not for protecting sensitive content.
- **Compression**: GZip (RFC 1952), chosen for simplicity and streaming support.
- **Format stability**:
  - The header is self‑contained and versioned.
  - Fields are intentionally simple (ints + PCM data) to make future experiments easy.

For any real‑world or production scenario, you should:

- Use properly managed, rotating encryption keys
- Employ authenticated encryption modes (e.g. AES‑GCM)
- Design and document a clear, versioned file format with forward/backward compatibility guarantees

---

## Development

### Running via Gradle

Instead of using the JAR directly, you can run via Gradle during development:

```bash
# Encode
./gradlew run --args="encode input.wav output.asph"

# Decode
./gradlew run --args="decode input.asph output.wav"

# Play
./gradlew run --args="play output.asph"

# Metadata
./gradlew run --args="metadata output.asph \"Title\" \"Artist\" \"Album\""
```

### Java Version

The project is configured for Java 21 toolchains:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

If you need to use a different Java version, adjust the toolchain config and re‑build.

---

## Limitations / Known Issues

- Input audio must be in a format that `AudioSystem` can read (typically PCM WAV and a few others, depending on platform/installed providers).
- Audio playback and key controls are handled via standard input/output; behavior can vary slightly depending on terminal/console.
- The AES key and IV are hard‑coded for simplicity. Changing them will break compatibility with existing ASPH files.
- There is no built‑in integrity check (e.g. MAC) on the encrypted payload; corrupted files may fail during decryption or decompression without a friendly message.

---

## Contributing

Suggestions, bug reports, and pull requests are welcome, especially for:

- Better audio resampling or broader format support
- Additional metadata fields or tagging systems
- Improved error handling and diagnostics
- Optional integrity checks or more robust format validation

Please open an issue describing what you’d like to change or add.

---

## License

This project is provided as‑is, for experimentation and learning.  
You are responsible for ensuring that any audio you process and distribute complies with the applicable licenses and rights.