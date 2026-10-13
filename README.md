# excel-upload

A small Spring Boot service that converts an uploaded Excel workbook (`.xls` or `.xlsx`) into JSON. **Each sheet becomes its own JSON file, named after the sheet.** You can download the files one at a time or together as a zip.

In each sheet, the first row is used as the column headers. Each following row becomes a JSON object keyed by those headers. For example, a workbook with a `Customers` sheet and an `Orders 2024` sheet produces `Customers.json` and `Orders 2024.json`:

| name | city |
| ---- | ---- |
| Ann  | Oslo |
| Bob  |      |

```json
[
  { "name": "Ann", "city": "Oslo" },
  { "name": "Bob", "city": "" }
]
```

## Requirements

- Java 17+
- Nothing else. The Gradle wrapper (8.14.2) downloads its own Gradle.

## Running

```bash
./gradlew bootRun
```

The service listens on port **20000**. Open [http://localhost:20000](http://localhost:20000) for the web UI.

To build a runnable jar:

```bash
./gradlew bootJar
java -jar build/libs/excel-upload-0.0.1-SNAPSHOT.jar
```

## Web UI

The service includes a browser UI at `/`, served from `src/main/resources/static/`. It is plain HTML, CSS and JavaScript, so there's no separate frontend build. With it you can:

- Drag and drop a workbook, or browse for one. Files over 25 MB are rejected before upload.
- See the list of generated files: one `<sheet name>.json` per sheet, with row count, size and a "hidden sheet" badge where it applies.
- Download a single file, or **Download all (.zip)**.
- Preview any sheet as a table (blank cells shown as `—`) or as formatted JSON, filter its rows, copy the JSON, or download the filtered rows as CSV.
- Read error messages from the API, for example when a file isn't a valid workbook.

The UI follows the system light or dark theme.

## API

All error responses are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Could not read Excel file: ...",
  "instance": "/conversions"
}
```

### `POST /conversions`: convert every sheet

Multipart form upload with the workbook in a field named `file`.

```bash
curl -F "file=@shop.xlsx" http://localhost:20000/conversions
```

**201 Created**, with a `Location` header pointing at the conversion:

```json
{
  "id": "3343d875-9aab-4c43-9da1-8da8d6ecaa93",
  "sourceFile": "shop.xlsx",
  "expiresAt": "2026-10-13T23:02:02.718690Z",
  "url": "http://localhost:20000/conversions/3343d875-…",
  "zipUrl": "http://localhost:20000/conversions/3343d875-…/zip",
  "files": [
    {
      "fileName": "Customers.json",
      "sheetName": "Customers",
      "hidden": false,
      "rows": 2,
      "sizeBytes": 82,
      "url": "http://localhost:20000/conversions/3343d875-…/files/0"
    },
    {
      "fileName": "Orders 2024.json",
      "sheetName": "Orders 2024",
      "hidden": false,
      "rows": 2,
      "sizeBytes": 78,
      "url": "http://localhost:20000/conversions/3343d875-…/files/1"
    }
  ]
}
```

**400:** the file is empty, isn't a readable workbook, or is password-protected.

**413:** the file is over the upload limit.

### `GET /conversions/{id}`

Returns the same listing again. **404** if the conversion is unknown or expired.

### `GET /conversions/{id}/files/{index}`

Downloads one sheet's JSON. `index` is the position in the `files` list, starting at 0. The response is sent as an attachment named after the sheet, for example `Orders 2024.json`.

```bash
curl -OJ http://localhost:20000/conversions/<id>/files/1
```

**404** if the conversion or index doesn't exist.

### `GET /conversions/{id}/zip`

Downloads all of the sheet files in one zip, named after the uploaded workbook (`shop.xlsx` becomes `shop.zip`).

```bash
curl -OJ http://localhost:20000/conversions/<id>/zip
```

### Expiry

Converted files are kept **in memory** for 30 minutes (`expiresAt`). Up to 100 conversions are kept at once; when that's full, the oldest is dropped. After that, the links return 404 and the workbook has to be uploaded again. Both limits are configurable (see [Configuration](#configuration)).

### `POST /upload`: first sheet only (legacy)

The original endpoint is unchanged. It returns the first sheet's rows directly as a JSON array and stores nothing.

```bash
curl -F "file=@shop.xlsx" http://localhost:20000/upload
```

### File naming

- Each file is named `<sheet name>.json`.
- Characters that aren't safe in file names (`\ / : * ? " < > |` and control characters) become `_`. Leading dots and surrounding spaces are removed.
- A sheet whose name ends up empty is named `sheet<N>.json` (1-based sheet position).
- If two sheets end up with the same file name (compared case-insensitively), the later one gets a suffix: `Q1_Q2.json`, `Q1_Q2_2.json`.
- Hidden sheets are included and have `"hidden": true` in the listing.
- An empty sheet produces a file containing `[]`.

### Conversion rules

These apply to every sheet:

- **All values are strings**, formatted the way Excel shows them. Numbers, dates, booleans and formula results come back as their displayed text.
- Missing or blank cells become `""`.
- Rows that are entirely blank are skipped.
- An empty header cell is named `column<N>` (1-based column number).
- Duplicate headers get a suffix (`name`, `name_2`, `name_3`) so no column is lost.
- Keys stay in column order.

## Configuration

Settings live in `src/main/resources/application.properties`:

| Property                                      | Default   | Purpose                                        |
| --------------------------------------------- | --------- | ---------------------------------------------- |
| `server.port`                               | `20000` | HTTP port                                      |
| `spring.servlet.multipart.max-file-size`    | `25MB`  | Maximum upload size                            |
| `spring.servlet.multipart.max-request-size` | `26MB`  | Maximum request size (file plus form overhead) |
| `server.tomcat.accesslog.enabled`           | `true`  | Tomcat access log                              |
| `excel-upload.conversions.ttl`              | `30m`   | How long converted files stay downloadable     |
| `excel-upload.conversions.max-entries`      | `100`   | Maximum conversions kept in memory             |

You can override any of them at startup, for example `--server.port=8080`.

## Testing

```bash
./gradlew test
```

## Further reading

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the service is put together.
