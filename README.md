# AudioSphere

AudioSphere is a cross‑platform, experimental command‑line tool for working with a custom encrypted audio container format (`.asph`), called **ASPH v4**.

The project is implemented in **Java 21**, targets Linux, macOS, and Windows, and uses only standard Java APIs.  
It is **experimental**, not designed or reviewed for production use, security‑critical scenarios, or long‑term archival.

For full details of the file format, see the [ASPH v4 Specification](docs/specs.md).

---

## Status & Scope

> **Experimental software – use at your own risk.**  
> The format, APIs, and behavior may change at any time.  
> Do not rely on this project for:
> - Strong content protection or DRM  
> - Long‑term stable file formats  
> - Critical audio workflows in production environments  

The intent of this project is to:

- Explore a **custom audio container format** (ASPH v4).
- Demonstrate a simple **PCM → container → GZip → AES‑CBC** pipeline in Java.
- Provide a small CLI for learning, experimentation, and AI‑related tooling.

---

## What is ASPH v4?

ASPH v4 is a **container format for PCM audio**. Conceptually:

- Input is a **WAV** file (PCM audio).
- Audio is converted to a normalized **PCM_SIGNED, little‑endian** format that mirrors the source, clamped to:
  - up to **96 kHz** sample rate
  - up to **24‑bit** samples
  - up to **2 channels** (mono or stereo)
- The ASPH payload stores:
  - Magic: `ASPH`
  - Version: `0x04`
  - Sample rate, bit depth, channels
  - Raw PCM data
- This payload is:
  - **GZip‑compressed**, then
  - **AES‑128 CBC** encrypted (fixed key/IV in this prototype),
  - and wrapped in a tiny outer header with its own `ASPH` magic and length.
- Optionally, a **512‑byte metadata block** is appended at the end of the file, containing:
  - Title
  - Artist
  - Album

In other words, ASPH v4 is essentially **PCM in a small custom container**, compressed and encrypted, with basic tagging. It is intentionally simple and easy to parse.

For the full binary layout and rules, see:

- [ASPH v4 Specification](docs/specs.md)

---

## Features

- **Encode** WAV files into ASPH v4:
  - Input: **WAV only** (required, via Java `AudioSystem`).
  - Output PCM format mirrors the source (clamped to ≤ 96kHz, 24‑bit, 2ch).
  - Container:
    - Inner header: `ASPH` magic, version, sample rate, bits, channels.
    - PCM audio payload.
  - GZip compression.
  - AES‑CBC encryption with a fixed key/IV (for experimentation).

- **Decode** ASPH v4 files back into WAV:
  - Verifies magic and version.
  - Decrypts, decompresses, and reconstructs a standard WAV using the format stored in ASPH.

- **Play** ASPH v4 audio from the CLI:
  - Uses `javax.sound.sampled` (`SourceDataLine`) for playback.
  - Works on Linux, macOS, Windows (audio device required).
  - Interactive terminal controls:
    - `p` - Pause / Resume
    - `+` - Volume up
    - `-` - Volume down
    - `f` - Seek forward 10 seconds
    - `b` - Seek backward 10 seconds
    - `q` - Quit / stop playback
  - Optional loop mode.

- **Metadata handling**:
  - 512‑byte metadata block at the end of the ASPH file.
  - Stores UTF‑8:
    - Title
    - Artist
    - Album
  - Automatically used for “Now Playing” info during playback.

---

## Project Structure

```text
AudioSphere/
├── app/
│   ├── build.gradle          # Gradle build (application, Java 21)
│   └── src/
│       └── main/
│           ├── java/
│           │   └── dev/
│           │       └── thedomcraft/
│           │           └── audiosphere/
│           │               ├── Main.java              # CLI entrypoint
│           │               ├── AudioSphereEncoder.java# ASPH v4 encode/decode
│           │               ├── AudioSpherePlayer.java # CLI player with controls
│           │               ├── MetadataHandler.java   # 512‑byte metadata block
│           │               └── Utilities.java         # AES+GZip helpers
│           └── resources/
├── docs/specs.md           # Formal ASPH v4 format specification
├── README.md                 # This file
├── settings.gradle
└── gradle/ + wrapper files
```

---

## Requirements

- **Java 21** (JDK 21 or newer).
- The included **Gradle wrapper** (`./gradlew` / `gradlew.bat`).
- A functioning audio output device (for `play`).

---

## Building

From the repository root:

```bash
./gradlew :app:clean :app:build
```

This produces a runnable JAR in:

```text
app/build/libs/
  app-4.0.0.jar
```

Run it with:

```bash
java -jar app/build/libs/app-4.0.0.jar <command> [args...]
```

---

## Usage

General form:

```bash
java -jar app/build/libs/app-4.0.0.jar <command> [args...]
```

Commands are intentionally minimal and CLI‑oriented.

### 1. Encode

Encode a WAV file into an ASPH v4 file:

```bash
java -jar app/build/libs/app-4.0.0.jar encode <input.wav> <output.asph>
```

- **Input** must be a WAV file.
- The encoder:
  - Validates the input is WAV.
  - Reads its audio format.
  - Clamps the format to:
    - 8–96 kHz sample rate,
    - 8–24 bits per sample (rounded to 8/16/24),
    - 1–2 channels.
  - Converts to PCM_SIGNED little‑endian with that format.
  - Writes the ASPH v4 payload.
  - Compresses (GZip).
  - Encrypts (AES‑CBC).
  - Writes outer header and encrypted data.

Example:

```bash
java -jar app/build/libs/app-4.0.0.jar encode song.wav song.asph
```

### 2. Decode

Decode an ASPH v4 file back into WAV:

```bash
java -jar app/build/libs/app-4.0.0.jar decode <input.asph> <output.wav>
```

Example:

```bash
java -jar app/build/libs/app-4.0.0.jar decode song.asph song_decoded.wav
```

- The decoder:
  - Validates outer magic (`ASPH`).
  - Reads encrypted length and ciphertext.
  - Decrypts and decompresses.
  - Parses inner header (`ASPH`, version=4, sampleRate, bits, channels).
  - Wraps the PCM data in a WAV container using that format.

### 3. Play

Play an ASPH v4 file on your system’s audio device:

```bash
java -jar app/build/libs/app-4.0.0.jar play <input.asph> [loop]
```

Examples:

```bash
# Play once
java -jar app/build/libs/app-4.0.0.jar play song.asph

# Play in loop
java -jar app/build/libs/app-4.0.0.jar play song.asph loop
```

During playback, controls are read from **stdin** (the terminal):

- `p` - Pause / Resume
- `+` - Volume up (0–100%)
- `-` - Volume down
- `f` - Seek forward 10 seconds
- `b` - Seek backward 10 seconds
- `q` - Stop playback

The player also reads metadata (if present) and displays:

- Title
- Artist
- Album
- Actual PCM format from ASPH: sample rate, bits, channels, version.

> Note: Because this uses standard input, behavior may differ in IDE consoles.  
> It works best in a normal terminal (e.g. bash, zsh, PowerShell).

### 4. Metadata

Attach or update metadata on an ASPH file:

```bash
java -jar app/build/libs/app-4.0.0.jar metadata <input.asph> <title> <artist> [album]
```

Example:

```bash
java -jar app/build/libs/app-4.0.0.jar metadata song.asph "My Title" "My Artist" "My Album"
```

- Writes a **512‑byte block at the end of the file**:
  - `int32` LE: title length, then UTF‑8 title bytes,
  - `int32` LE: artist length, then UTF‑8 artist bytes,
  - `int32` LE: album length, then UTF‑8 album bytes,
  - Zero padding for the remainder of 512 bytes.

See [ASPH v4 Specification](docs/specs.md) §6 for the exact layout.

### 5. Version

Print version info:

```bash
java -jar app/build/libs/app-4.0.0.jar version
```

---

## ASPH v4 Specification

The exact binary structure of ASPH v4 (headers, fields, ranges, and expected behaviors) is documented in:

- [`docs/specs.md`](docs/specs.md)

This is the reference document for:

- Outer container
- Inner ASPH payload
- PCM format semantics
- Encryption and compression
- Metadata block

If you plan to implement readers/writers in other languages or tools, start there.

---

## Security & Design Notes

- **Encryption**:
  - AES‑128 in CBC mode with PKCS#5/7 padding,
  - Fixed key and IV (hard‑coded) in this proof of concept.
  - This is **not a secure DRM system** or robust content protection mechanism.
  - Anyone with access to the repo/spec can decrypt ASPH files.

- **Compression**:
  - GZip (RFC 1952), chosen for simplicity and streaming support.

- **Format stability**:
  - ASPH v4 is versioned and self‑describing, but there are **no stability guarantees**.
  - Future versions may change header fields or add chunks.

For any real‑world or security‑critical use, you should:

- Use randomized keys/IVs per file or session.
- Add integrity/authentication (MAC or AEAD like AES‑GCM).
- Design a versioning strategy with forward/backward compatibility.

---

## Development

### Running via Gradle

During development you can run from the root using Gradle:

```bash
# Encode
./gradlew :app:run --args="encode input.wav output.asph"

# Decode
./gradlew :app:run --args="decode input.asph output.wav"

# Play
./gradlew :app:run --args="play output.asph"

# Metadata
./gradlew :app:run --args="metadata output.asph \"Title\" \"Artist\" \"Album\""
```

### Java Version

The project is configured to use **Java 21** via toolchains:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

If you need a different JDK version, update this setting and rebuild.

---

## Limitations / Known Issues

- **Input**:
  - Only **WAV** is officially supported in this proof of concept.
  - Other formats are not handled and will result in errors.
- **Audio playback**:
  - Uses `javax.sound.sampled`; device support and mixing behavior may vary by OS/JVM.
- **Static encryption parameters**:
  - Fixed key/IV are for experimentation only; do not use ASPH v4 as a secure container.
- **Integrity**:
  - No MAC or AEAD; corrupted payloads may fail with cryptic errors on decrypt/decompress.

For more details and edge cases, see [docs/specs.md](docs/specs.md).

---

## Contributing

Suggestions, bug reports, and pull requests are welcome, especially for:

- Better error messages and diagnostics.
- Extended metadata fields or additional chunks.
- Optional integrity checks (MAC, checksums).
- Porting ASPH v4 tooling to other languages.

If you plan bigger changes to the format, please reference the [ASPH v4 Specification](docs/specs.md) and propose a version bump or extension strategy.

---

## License

This project is released under the [MIT License](LICENSE).  
You are responsible for ensuring that any audio content you process and distribute complies with the applicable rights and licenses.