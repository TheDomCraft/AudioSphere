# ASPH v4 – AudioSphere PCM Container Format Specification

**Version:** 4  
**Status:** Experimental / Proof‑of‑Concept  
**Author:** TheDomCraft  
**License:** MIT (matching repository)

ASPH (AudioSphere PCM Header) is a very small, experimental container format for **PCM audio**. It is designed as a simple, deterministic structure for experimentation, tooling, and AI‑assisted workflows.

ASPH v4, as implemented in the Java proof‑of‑concept, is:

- A **container format** that wraps uncompressed PCM audio data.
- Self‑describing via a small binary header (magic, version, sample rate, bit depth, channels).
- Optionally augmented with a fixed‑size metadata block at the end of the file.
- Encapsulated with **GZip compression** and **AES‑128 CBC encryption** using a fixed key/IV in the current implementation.

It is **not** intended as a production file format, DRM system, or archival standard.

---

## 1. Overview

An ASPH v4 file on disk has three conceptual layers:

1. **Outer container**: identifies the file as ASPH and stores the length of the encrypted payload.
2. **Inner ASPH payload**: a header describing the PCM format, followed by raw PCM audio bytes.
3. **Optional metadata block**: a 512‑byte block at the end of the file, storing Title/Artist/Album.

High‑level layout on disk:

```text
+------------------------+
| Outer Header           |
|  "ASPH" (4 bytes)      |
|  length (4 bytes LE)   |
+------------------------+
| AES-CBC Encrypted      |
| GZip-Compressed Blob   |
+------------------------+
| Optional Metadata (512)|
+------------------------+
```

Decompressed + decrypted inner blob:

```text
+------------------------+
| Inner ASPH Header      |
|  "ASPH" (4 bytes)      |
|  version (1 byte)      |
|  sampleRate (4 LE)     |
|  bitsPerSample (4 LE)  |
|  channels (4 LE)       |
+------------------------+
| PCM Audio Data (...)   |
+------------------------+
```

---

## 2. Data Types and Endianness

Unless stated otherwise:

- **Integers** are 32‑bit signed, **little‑endian**.
- All PCM audio is **PCM_SIGNED**, **little‑endian**.
- Strings in metadata are **UTF‑8** encoded.

Terminology:

- `LE int32` – signed 32‑bit integer, little‑endian.
- `byte` – unsigned 8‑bit value (0–255).
- `PCM` – raw PCM samples, interleaved by channel.

---

## 3. Outer Container (File-Level Header)

This is the first structure in the `.asph` file on disk.

### 3.1 Structure

| Offset | Size (bytes) | Type     | Name          | Description                                     |
|--------|--------------|----------|---------------|-------------------------------------------------|
| 0      | 4            | bytes    | `magic`       | ASCII `"ASPH"`                                  |
| 4      | 4            | LE int32 | `length`      | Length of encrypted payload in bytes            |
| 8      | `length`     | bytes    | `ciphertext`  | AES‑CBC encrypted, GZip compressed inner blob   |
| 8+len  | optional 512 | bytes    | `metadata`    | Optional metadata block (see §6)                |

### 3.2 Magic

- Must be the ASCII bytes `0x41 0x53 0x50 0x48` (`"ASPH"`).
- If magic does not match, the file is not a valid ASPH container.

### 3.3 Length

- `length` is the size in bytes of the encrypted payload that follows.
- Must be > 0 and must not exceed the actual file size minus 8 bytes.

### 3.4 Ciphertext

- The payload is:
  1. Constructed as the **inner ASPH payload** (§4).
  2. GZip‑compressed (RFC 1952).
  3. Encrypted with **AES‑128 CBC** with PKCS#5/7 padding.

---

## 4. Inner ASPH Payload

This is the structure of the decrypted + decompressed data.

### 4.1 Structure

| Offset | Size (bytes) | Type     | Name           | Description                                       |
|--------|--------------|----------|----------------|---------------------------------------------------|
| 0      | 4            | bytes    | `magic`        | ASCII `"ASPH"` (inner identifier)                 |
| 4      | 1            | byte     | `version`      | ASPH version byte; for this spec, always `0x04`   |
| 5      | 4            | LE int32 | `sampleRate`   | Sample rate in Hz                                 |
| 9      | 4            | LE int32 | `bitsPerSample`| Bits per sample (per channel)                     |
| 13     | 4            | LE int32 | `channels`     | Number of channels (1 = mono, 2 = stereo)        |
| 17     | ...          | bytes    | `pcmData`      | PCM_SIGNED, little‑endian, interleaved samples   |

Total header size is fixed at **17 bytes**.

### 4.2 Magic

- Must again be `"ASPH"` to guard against corruption or misalignment.

### 4.3 Version

- For ASPH v4: **`version = 0x04`**.
- Parsers must:
  - verify version is 4,  
  - or handle future versions carefully (e.g., reject or use backward‑compat logic).

### 4.4 Sample Rate

- `sampleRate` is the PCM sample rate in Hz.
- In the current implementation:
  - Derived from the source WAV file,
  - Then **clamped** to `[8000, 96000]`.

### 4.5 Bits Per Sample

- `bitsPerSample` is the number of bits per one sample per channel.
- In the current implementation:
  - Derived from the WAV’s `AudioFormat.getSampleSizeInBits()`,
  - If that is ≤ 0, a default of 16 is assumed,
  - Then clamped to `[8, 24]`,
  - Rounded to nearest multiple of 8 (8, 16, or 24).

### 4.6 Channels

- `channels` is the number of audio channels.
- In the current implementation:
  - Derived from the WAV’s `AudioFormat.getChannels()`,
  - Clamped to `[1, 2]`.

### 4.7 PCM Audio Data

- The bytes immediately following the header are **raw PCM_SIGNED, little‑endian, interleaved by channel**, e.g.:

  - For stereo (`channels = 2`, `bitsPerSample = 16`):
    - Samples per frame: Left16 LE, Right16 LE, Left16 LE, Right16 LE, ...
  - For mono (`channels = 1`, `bitsPerSample = 24`):
    - Samples: 3 bytes each, little‑endian, one channel.

- Frame size (in bytes):
  ```text
  frameSizeBytes = channels * (bitsPerSample / 8)
  ```

- The number of frames is:
  ```text
  numFrames = pcmData.length / frameSizeBytes
  ```
  (integer division; leftover bytes indicate corruption/truncation).

---

## 5. Encryption and Compression

### 5.1 Compression

- The **inner payload** (header + `pcmData`) is compressed using **GZip**.
- Implementation behavior:
  - Standard Java `GZIPOutputStream` with default compression settings.
  - On decode, Java `GZIPInputStream` is used to decompress.

### 5.2 Encryption

- After compression, the byte sequence is encrypted using:

  - **Algorithm:** AES
  - **Mode:** CBC (Cipher Block Chaining)
  - **Padding:** PKCS#5 / PKCS#7
  - **Key Size:** 128‑bit (16 bytes)
  - **IV Size:** 128‑bit (16 bytes)

- The current implementation uses a **fixed key and IV**:

  ```text
  Key (hex, 16 bytes):
    21 43 65 87 09 BA DC FE 13 57 9B DF 02 46 8A CE

  IV (hex, 16 bytes):
    12 34 56 78 90 AB CD EF 11 22 33 44 55 66 77 88
  ```

- Purpose:
  - **Experimentation and learning only**, not secure DRM.
  - Anyone with access to this spec and these constants can decrypt an ASPH file.

> For any real‑world or security‑critical use, keys/IVs must be:
> - Randomly generated per file/session,
> - Protected out‑of‑band,
> - Paired with authenticated encryption (e.g., AES‑GCM or HMAC).

---

## 6. Metadata Block (Optional, 512 bytes)

At the very end of the `.asph` file, an optional 512‑byte metadata block may be present. It is **not encrypted** and **not compressed**. It is appended after the outer container’s ciphertext.

### 6.1 Layout

If present, it is exactly 512 bytes, structured as:

| Offset (within block) | Size (bytes) | Type     | Name       | Description                            |
|------------------------|--------------|----------|------------|----------------------------------------|
| 0                      | 4            | LE int32 | `titleLen` | Length of `title` in bytes (UTF‑8)     |
| 4                      | `titleLen`   | bytes    | `title`    | UTF‑8 string                           |
| 4+titleLen             | 4            | LE int32 | `artistLen`| Length of `artist` in bytes (UTF‑8)    |
| ...                    | `artistLen`  | bytes    | `artist`   | UTF‑8 string                           |
| ...                    | 4            | LE int32 | `albumLen` | Length of `album` in bytes (UTF‑8)     |
| ...                    | `albumLen`   | bytes    | `album`    | UTF‑8 string                           |
| ...                    | remaining    | bytes    | `padding`  | Zero‑filled to reach 512 bytes total   |

### 6.2 Constraints

- Total size of:
  ```text
  4 + titleLen + 4 + artistLen + 4 + albumLen
  ```
  must be **≤ 512**.

- If not, metadata writing should fail (or truncate).
- Any unused bytes after `album` are written as `0x00`.

### 6.3 Reading Behavior

- If file size `< 512`, no metadata is assumed.
- Otherwise:
  - The reader seeks to `fileSize - 512`.
  - Reads the block.
  - Extracts lengths and strings carefully, ensuring each length does not exceed remaining bytes.

---

## 7. Encoding Process (Reference Implementation)

The Java reference encoder (AudioSphere v4) follows this pipeline for **encoding**:

1. **Input**: Path to a WAV file (`.wav`).
2. Validate input is a WAV (via `AudioSystem.getAudioFileFormat(file).getType()`).
3. Get `AudioInputStream` + base `AudioFormat` from the WAV.
4. Derive target PCM format:
   - `sampleRate = clamp(base.sampleRate, 8000, 96000)`
   - `bitsPerSample = clamp(base.sampleSizeInBits, 8, 24)`, rounded to nearest multiple of 8 (8, 16, or 24)
   - `channels = clamp(base.channels, 1, 2)`
   - `AudioFormat` target = `PCM_SIGNED`, little‑endian, interleaved, with this rate/bits/channels.
5. Convert to target PCM via `AudioSystem.getAudioInputStream(targetFormat, originalStream)`.
6. Read all PCM bytes into memory.
7. Build the **inner payload**:
   - Write `"ASPH"`, `version=0x04`.
   - Write `sampleRate`, `bitsPerSample`, `channels` as LE int32.
   - Write `pcmData`.
8. GZip compress inner payload.
9. AES‑CBC encrypt compressed data with fixed key/IV.
10. Write outer container:
    - `"ASPH"` (magic),
    - `length` (encrypted size),
    - `ciphertext`.
11. Optionally append 512‑byte metadata block.

---

## 8. Decoding Process (Reference Implementation)

For **decoding** `.asph` to WAV:

1. Open file, read first 4 bytes:
   - Verify `"ASPH"`.
2. Read next 4 bytes:
   - Interpret as `length` of ciphertext (LE int32).
3. Read `length` bytes as `encrypted`.
4. Decrypt:
   - AES‑CBC with fixed key/IV.
5. Decompress:
   - GZip.
6. Parse inner payload:
   - Read `"ASPH"` and `version` (expect 0x04).
   - Read `sampleRate`, `bitsPerSample`, `channels`.
   - Remaining bytes are `pcmData`.
7. Create PCM `AudioFormat` from header values.
8. Wrap `pcmData` as an `AudioInputStream`.
9. Write to WAV (`AudioSystem.write(.., WAVE, outFile)`).

Playback uses essentially the same decode steps, but streams the PCM into a `SourceDataLine` instead of constructing a WAV file.

---

## 9. Validation and Error Handling

A robust parser/encoder should:

- Validate magic strings (`"ASPH"`) at both outer and inner levels.
- Validate `length` against file size.
- Validate `version`:
  - Accept 4; warn or reject other versions.
- Validate header values:
  - `sampleRate` in [8000, 96000].
  - `bitsPerSample` in {8, 16, 24} (or at least ≤ 32 and multiple of 8).
  - `channels` in {1, 2}.
- Validate metadata lengths:
  - Each `Len` field must be ≥ 0 and ≤ remaining bytes in the 512‑byte block.

On any invalid condition, decoders are expected to throw or abort rather than continue with corrupt data.

---

## 10. Intended Use and Limitations

ASPH v4, as currently implemented, is **experimental** and primarily intended for:

- Prototyping and demonstrations,
- AI/ML workflows that benefit from a simple, well‑defined binary audio container,
- Learning about container formats, encryption, and PCM handling in Java.

It is **not** designed for:

- Production streaming or broadcasting,
- Strong content protection (key/IV are static),
- Long‑term archival or compatibility guarantees.

Any future version of ASPH may change header fields, add new sections, or adopt different cryptographic practices.

---

## 11. Example Pseudocode (Parsing ASPH v4)

```pseudo
function parseAsphV4(filePath):
    file = open(filePath, "rb")

    // Outer header
    magicOuter = file.readBytes(4)
    if magicOuter != "ASPH":
        error("Not an ASPH file")

    length = readInt32LE(file)   // ciphertext length
    ciphertext = file.readBytes(length)

    // Decrypt + decompress
    compressed = aesCbcDecrypt(ciphertext, key, iv)
    inner = gzipDecompress(compressed)

    // Inner header
    cursor = 0
    magicInner = inner[cursor : cursor+4]; cursor += 4
    if magicInner != "ASPH":
        error("Invalid inner magic")

    version = inner[cursor]; cursor += 1
    if version != 0x04:
        warn("Unknown ASPH version")

    sampleRate = readInt32LE(inner, cursor); cursor += 4
    bitsPerSample = readInt32LE(inner, cursor); cursor += 4
    channels = readInt32LE(inner, cursor); cursor += 4

    pcmData = inner[cursor : end]

    return {
        sampleRate,
        bitsPerSample,
        channels,
        pcmData
    }
```

---

## 12. Future Directions

Potential future extensions could include:

- Support for **additional chunks**, e.g. loop points, cue markers.
- **Integrity protection**, e.g. HMAC or AES‑GCM.
- Optional **lossy codecs** inside ASPH (e.g. Opus or AAC payload instead of raw PCM).
- A more robust **version negotiation** mechanism.

For now, ASPH v4 is intentionally minimal, focusing on clarity and ease of implementation across languages and platforms.

---