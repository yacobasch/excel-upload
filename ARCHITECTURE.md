# Architecture

## Overview

excel-upload is a small Spring Boot web service. It converts each sheet of an uploaded Excel workbook into its own JSON file. The converted files are kept **in memory** for a limited time so they can be downloaded one at a time or as a zip. There's no database, no disk storage and no outbound calls.

```
 browser ──GET /──▶ static/index.html, app.js, app.css

 client ──multipart POST /conversions──▶ Tomcat (multipart size limits)
                                            │
                                            ▼
                                   ConversionController ──errors──▶ ApiExceptionHandler ─▶ 400 / 404 ProblemDetail
                                            │
                                            ▼
                                   ConversionService
                                    │            │
                     UploadService.parseAll      │ Jackson: one pretty-printed JSON byte[] per sheet
                     (Apache POI)                ▼
                                          ConversionStore  (in memory, TTL + max entries)
                                            ▲
 client ──GET /conversions/{id}[/files/{n} | /zip]──┘   ─▶ JSON listing, sheet file, or zip built on demand
```

The legacy `POST /upload` goes through `UploadController` and `UploadService.parse`. It returns the first sheet directly and stores nothing.

## Tech stack

| Concern | Choice |
| --- | --- |
| Language / runtime | Java 17 (Gradle toolchain) |
| Framework | Spring Boot 3.5 (`spring-boot-starter-web`, embedded Tomcat) |
| Excel parsing | Apache POI 5.4 (`poi` for `.xls`, `poi-ooxml` for `.xlsx`) |
| JSON | Jackson, via Spring MVC |
| Zip | `java.util.zip` |
| Build | Gradle 8.14 wrapper |
| Tests | JUnit 5, Spring `MockMvc`, AssertJ |

## Components

All code is under `src/main/java/dev/mj/excelupload/`.

### `ExcelUploadApplication`

The Spring Boot entry point. It extends `SpringBootServletInitializer`, so the app can also be deployed as a WAR to an external servlet container. The WAR plugin isn't currently enabled in `build.gradle`; the default artifact is an executable jar.

### `controller/ConversionController`

The HTTP layer for multi-sheet conversion, mapped under `/conversions`.

| Endpoint | Returns |
| --- | --- |
| `POST /conversions` | `201 Created` + `Location`, with a `ConversionResponse` listing every file and its download link |
| `GET /conversions/{id}` | The same `ConversionResponse` |
| `GET /conversions/{id}/files/{index}` | One sheet's JSON as an attachment |
| `GET /conversions/{id}/zip` | All the sheet files as `<workbook name>.zip` |

- Download links are absolute and built with `ServletUriComponentsBuilder.fromCurrentContextPath()`, so they respect the server's context path.
- Files are addressed by **index** rather than by name in the URL. Sheet names can contain spaces, accents and other characters that are awkward in paths; the real name is sent in `Content-Disposition`.
- `Content-Disposition` uses a plain `filename="…"` for ASCII names. Only non-ASCII names get the RFC 5987 `filename*=UTF-8''…` form, so simple clients such as `curl -J` keep readable names.
- The response types `ConversionResponse` and `FileLink` are records nested in the controller.

### `controller/UploadController`

The legacy single-sheet endpoint, `POST /upload`. It returns `UploadService.upload(file)`, which is the first sheet's rows.

### `controller/ApiExceptionHandler`

A `@RestControllerAdvice` shared by both controllers. It turns exceptions into `ProblemDetail` responses:

| Exception | Status | Cause |
| --- | --- | --- |
| `InvalidUploadException` | 400 | Empty upload |
| `IOException` | 400 | Unreadable or unrecognized file |
| `EncryptedDocumentException` | 400 | Password-protected workbook |
| `UnsupportedFileFormatException` | 400 | Wrong Office format |
| `ConversionNotFoundException` | 404 | Unknown or expired conversion, or a file index out of range |

Anything else falls through to Spring's default 500 handling. Oversized uploads get a 413 from Spring's multipart handling before reaching a controller.

### `service/UploadService`

Parses workbooks with Apache POI.

| Method | Purpose |
| --- | --- |
| `upload(MultipartFile)` / `parse(InputStream)` | First sheet only, as `List<Map<String,String>>` |
| `uploadAllSheets(MultipartFile)` / `parseAll(InputStream)` | Every sheet in workbook order, as `List<SheetData>` |

`SheetData` is a record holding the sheet name, a hidden flag (hidden or very hidden) and the rows.

The workbook is opened with `WorkbookFactory.create`, which detects `.xls` or `.xlsx` from the file content, not the filename, and is closed by try-with-resources. Each sheet is read the same way:

1. The first physical row is the header row.
2. Empty headers become `column<N>`, and duplicates get a `_2`, `_3`, … suffix.
3. Each later row is read **by column index**, using `MissingCellPolicy.RETURN_BLANK_AS_NULL`. Missing cells map to `""`, so values never shift columns.
4. Cells are formatted with `DataFormatter` plus a `FormulaEvaluator`. Every value is the string Excel would show.
5. Missing and entirely blank rows are skipped. Column order is kept with a `LinkedHashMap`.

### `conversion/ConversionService`

- `convert(MultipartFile)`:
  1. Calls `UploadService.uploadAllSheets`.
  2. Gives each sheet a file name using `FileNames.Allocator`.
  3. Serializes each sheet's rows to pretty-printed JSON bytes with the Spring-configured `ObjectMapper`.
  4. Saves the result in `ConversionStore`.
- `zip(Conversion)` builds the zip on demand from the stored bytes, with one entry per sheet file.

### `conversion/ConversionStore`

An in-memory store of `Conversion` records, keyed by a random UUID.

- Backed by a `LinkedHashMap` in insertion order, so the first entry is always the oldest. All access is `synchronized`.
- Expired entries are evicted lazily on every `save` and `find`, so no scheduler thread is needed.
- When the store holds `max-entries`, the oldest conversion is dropped before a new one is saved.
- The TTL and size come from `excel-upload.conversions.ttl` (default `30m`) and `excel-upload.conversions.max-entries` (default `100`).
- A package-private constructor takes a `Supplier<Instant>` clock, so tests can control time.

### `conversion/Conversion`, `SheetFile`, `FileNames`

- `Conversion` is a record: id, source file name, created and expiry times, and the list of `SheetFile`s. `baseName()` gives the zip name.
- `SheetFile` is a record: file name, sheet name, hidden flag, row count and the JSON bytes.
- `FileNames.sanitize` replaces `\ / : * ? " < > |` and control characters with `_`, trims spaces and strips leading dots.
- `FileNames.Allocator` hands out unique names, compared case-insensitively (as on Windows and default macOS file systems and in zip extractors). It falls back to `sheet<N>.json` when a name sanitizes to nothing.

### `service/InvalidUploadException`, `conversion/ConversionNotFoundException`

Small runtime exceptions mapped to 400 and 404 by `ApiExceptionHandler`.

### Web UI (`src/main/resources/static/`)

A static single-page UI that Spring Boot serves at `/` from the classpath `static/` folder. There's no framework or build step.

- `index.html` contains the markup: drop zone, status line, the generated-files panel, and the preview with its table and JSON views.
- `app.js`:
  - Posts the file to the relative URL `conversions` and renders the returned file list. Each file has **Preview** and **Download** buttons, and there's a **Download all (.zip)** link.
  - Downloads use the server's links directly, so the browser saves files under the names from `Content-Disposition`.
  - **Preview** fetches one sheet's JSON. The first sheet is previewed automatically.
  - Filtering, the table/JSON view switch, clipboard copy and CSV export of the previewed sheet all happen in the browser. The CSV starts with a UTF-8 BOM so Excel detects the encoding.
  - All values are inserted with `textContent`, so spreadsheet content can't inject HTML.
  - Files over 25 MB are rejected before upload. Error messages come from the API's `ProblemDetail.detail`.
- `app.css` contains the styles. Theme colors are CSS custom properties with a `prefers-color-scheme: dark` variant.

## Design decisions

- **Server-side files with download links.** Each sheet's JSON is generated once and served from memory. Clients get stable, shareable links and a ready-made zip, at the cost of the service holding state for the TTL.
- **In memory rather than on disk.** There are no temp files to clean up, and nothing is ever written to a path built from client input. The trade-off is that conversions don't survive a restart and are bounded by heap size.
- **Zip built on demand.** Only the per-sheet bytes are stored. The zip is assembled when requested, so memory isn't spent on two copies of every conversion.
- **Unguessable IDs.** Conversion IDs are random UUIDs. Anyone who has the link can download the files until they expire, so treat links as private.
- **All values are strings.** This keeps the output predictable and matches what users see in Excel. Clients convert numbers and dates themselves.
- **Index-based cell access.** POI's row iterator skips blank cells, which misaligns columns, so reading by column index avoids that.
- **Legacy endpoint kept.** `POST /upload` is unchanged and stateless, so existing callers aren't affected.

## Limits and operational notes

- **Size limits.** Uploads are capped at 25MB per file and 26MB per request.
- **Parsing memory.** POI's `usermodel` API loads the whole workbook into the heap, so a 25MB `.xlsx` can use several hundred MB while it's parsed.
- **Storage memory.** Stored JSON is often larger than the source workbook. The worst case held is roughly `max-entries` × the JSON size of the largest workbooks. Lower `max-entries` or `ttl` on small heaps.
- **Horizontal scaling.** The store is local to each instance, so a download link only works on the instance that did the conversion. To run more than one instance, either use sticky sessions or replace `ConversionStore` with a shared store (Redis, S3 with expiry rules, etc.). Its `save`/`find` interface is the only seam to change.
- **Restarts** discard all conversions.
- **Streaming.** If larger files become a requirement, switch `.xlsx` parsing to POI's streaming SAX reader (`XSSFReader`), and write sheet JSON to disk or object storage.
- **Logging.** Tomcat access logging is enabled. Application logging uses Spring Boot's default (Logback). POI's `log4j-api` calls are routed to SLF4J by Boot's `log4j-to-slf4j` bridge; no `log4j-core` is on the classpath.
- **Security.** There's no authentication. Download links act as bearer tokens until they expire. Put the service behind a gateway, or add Spring Security, before exposing it publicly.

## Testing

| Test | Scope |
| --- | --- |
| `ExcelUploadApplicationTests` | The full Spring context starts. |
| `controller/UploadControllerTests` | Legacy `/upload` endpoint. Number, date and formula formatting, a missing cell, blank-row skipping, and 400s for non-Excel and empty uploads. |
| `controller/ConversionControllerTests` | Full multi-sheet flow on a four-sheet workbook: file listing and names (including a sanitized collision and a hidden sheet), single-file download with `Content-Disposition`, zip contents, 404 for unknown conversions or indexes, 400 for non-Excel files. |
| `conversion/ConversionStoreTests` | TTL expiry with a controllable clock, oldest-first eviction when full, and file name sanitizing and de-duplication. |

## Possible extensions

- A shared `ConversionStore` (Redis or object storage) for multi-instance deployments
- An option to skip hidden or empty sheets
- A typed output mode that returns numbers and booleans as JSON types
- Streaming parsing for large files
- An OpenAPI description via `springdoc-openapi`
