# Cheap Shot Page Sync Project Format

This document describes the portable Page Sync archive format implemented by `PageSyncProjectArchive.kt`.

The intent is to let Cheap Shot, ChatGPT-assisted workflows, scripts, and other tools exchange complete or partially timed picture-book / video-book sessions without guessing manifest field names.

## File extension and MIME type

Recommended file extension:

```text
.cheapshot-pagesync
```

MIME type:

```text
application/vnd.cheapshot.pagesync+zip
```

The project is a normal ZIP archive. Do not wrap the ZIP in another archive.

Cheap Shot's Android importer may also receive the file as `application/zip` or `application/octet-stream` when Android or a browser loses the custom MIME type.

## Required archive layout

A normal project contains:

```text
Example.cheapshot-pagesync
|
|-- pagesync.json
|-- audio/
|   `-- soundtrack.mp3
`-- pages/
    |-- page_0001.png
    |-- page_0002.png
    |-- page_0003.png
    `-- ...
```

Paths stored in the manifest are relative to the archive root.

The importer rejects absolute paths and path traversal outside the extracted project root.

## Manifest file

The manifest must be named exactly:

```text
pagesync.json
```

Current writer version: **2**.

Cheap Shot currently accepts versions **1** and **2**.

### Complete version 2 example

```json
{
  "format": "cheapshot.pagesync",
  "version": 2,
  "projectName": "Binding Rites - Episode 1",
  "canvas": {
    "width": 1080,
    "height": 1920
  },
  "audio": {
    "path": "audio/soundtrack.mp3",
    "durationMs": 390480
  },
  "sequence": [
    {
      "path": "pages/page_0001.png",
      "cueMs": 0
    },
    {
      "path": "pages/page_0002.png",
      "cueMs": 5000
    },
    {
      "path": "pages/page_0003.png",
      "cueMs": 12100
    }
  ]
}
```

## Manifest fields

### Root

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `format` | string | yes | Must be exactly `cheapshot.pagesync`. |
| `version` | integer | yes | `1` or `2`. New writers should use `2`. |
| `projectName` | string | recommended | Display name restored by Page Sync. |
| `canvas` | object | written by Cheap Shot | Project canvas metadata. Current saver writes 1080 x 1920. |
| `audio` | object | yes | Soundtrack information. |
| `sequence` | array | yes | Ordered visual sequence. |
| `checksums` | object | optional | Optional integrity information accepted by the importer. |

### `canvas`

```json
{
  "width": 1080,
  "height": 1920
}
```

The current Page Sync saver writes a vertical 1080 x 1920 canvas. Keep this metadata in externally generated archives even though the current importer does not depend on it for opening a project.

### `audio`

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `path` | string | yes | Relative path to the soundtrack inside the ZIP. |
| `durationMs` | integer | recommended | Soundtrack duration in milliseconds. Use the real media duration. |

Example:

```json
"audio": {
  "path": "audio/soundtrack.mp3",
  "durationMs": 390480
}
```

The referenced audio file must physically exist in the archive.

### `sequence`

`sequence` is an ordered array. Array order is page order.

Each entry contains:

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `path` | string | yes | Relative path to the page image inside the ZIP. |
| `cueMs` | integer | complete project: yes | Start time of this page in milliseconds. |

Example:

```json
"sequence": [
  { "path": "pages/page_0001.png", "cueMs": 0 },
  { "path": "pages/page_0002.png", "cueMs": 5000 },
  { "path": "pages/page_0003.png", "cueMs": 12100 }
]
```

The current saver names pages as zero-padded files such as `page_0001.png`, but the importer follows the paths in the manifest rather than requiring that filename pattern.

## Cue rules

A Page Sync cue is the **start time of the corresponding page**.

Rules enforced by the current implementation:

1. The first cue must be exactly `0` ms.
2. Cues must form a contiguous prefix starting with page 1.
3. Cue times must be increasing.
4. The minimum gap between adjacent cues is **80 ms**.
5. If `audio.durationMs` is greater than zero, the final captured cue must begin before the end of the soundtrack.
6. A complete project has one cue for every sequence item.

The last page has no explicit end cue. It holds until `audio.durationMs`.

### Partial timing in version 2

Version 2 permits an in-progress Page Sync session.

Timed pages come first and include `cueMs`. Later untimed pages omit `cueMs` entirely.

Valid partial example:

```json
"sequence": [
  { "path": "pages/page_0001.png", "cueMs": 0 },
  { "path": "pages/page_0002.png", "cueMs": 4810 },
  { "path": "pages/page_0003.png", "cueMs": 9720 },
  { "path": "pages/page_0004.png" },
  { "path": "pages/page_0005.png" }
]
```

Invalid example:

```json
"sequence": [
  { "path": "pages/page_0001.png", "cueMs": 0 },
  { "path": "pages/page_0002.png" },
  { "path": "pages/page_0003.png", "cueMs": 12000 }
]
```

Once a cue is omitted, all later pages must also omit `cueMs`.

Version 1 does **not** permit incomplete timing.

## Supported media extensions

The current saver recognizes these common content types and extensions.

### Images

- PNG -> `.png`
- JPEG/JPG -> `.jpg`
- WebP -> `.webp`
- HEIC/HEIF -> `.heic`
- GIF -> `.gif`

### Audio

- WAV -> `.wav`
- MP3 / MPEG audio -> `.mp3`
- MP4 audio / AAC / M4A -> `.m4a`
- FLAC -> `.flac`
- OGG -> `.ogg`

Externally generated archives should prefer normal, correctly named media extensions instead of the saver's generic `.img` / `.audio` fallback.

## Optional SHA-256 checksums

The importer understands an optional checksum block:

```json
"checksums": {
  "algorithm": "SHA-256",
  "files": {
    "audio/soundtrack.mp3": "<hex sha256>",
    "pages/page_0001.png": "<hex sha256>",
    "pages/page_0002.png": "<hex sha256>"
  }
}
```

If present with algorithm `SHA-256`, every listed target must exist and match the supplied digest or import fails.

The built-in version 2 saver does not currently add this block automatically, so checksums are optional interoperability metadata rather than a requirement.

## Import validation limits

Current importer limits:

- Maximum pages: **1,000**
- Maximum archive entries: **1,100**
- Maximum extracted size of one file: **350 MiB**
- Maximum total extracted archive size: **2,000 MiB**

## External-project generation checklist

When generating a Page Sync project outside Cheap Shot:

1. Put the soundtrack and every page image inside one ZIP archive.
2. Add root `pagesync.json`.
3. Set `format` to `cheapshot.pagesync`.
4. Set `version` to `2`.
5. Store page order in `sequence` order.
6. Set the first page cue to `0`.
7. Use millisecond cue start times with at least 80 ms between adjacent cues.
8. Use the real soundtrack duration for `audio.durationMs`.
9. Ensure every manifest path exists inside the ZIP.
10. Keep all paths relative and inside the archive.
11. For a finished project, supply one cue for every page.
12. Rename the ZIP with the `.cheapshot-pagesync` extension for normal Android import.
13. Optionally add SHA-256 checksums for externally packaged archives.

## Timing philosophy

The archive format does not require equally spaced slides. Page Sync is designed for performed or editorial timing: each `cueMs` should represent the moment the narration, music, or story beat calls for the next image.

For narrated picture books and webtoon video books, prefer page turns aligned to spoken/page beats over dividing the soundtrack into equal durations.

## Canonical implementation

The source of truth is:

```text
app/src/main/java/com/novacut/editor/pagesync/PageSyncProjectArchive.kt
app/src/main/java/com/novacut/editor/pagesync/PageSyncTimeline.kt
```

If this documentation and the implementation ever disagree, update this document with the implementation change in the same pull request.
