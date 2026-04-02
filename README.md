# OpenAPI Parser

`OpenAPI Parser` is a Burp Suite extension for authorized API security testing.
It parses OpenAPI/Swagger definitions and generates ready-to-use HTTP requests for manual assessment.

Use this project only in legal, authorized environments.

## Features

- Supports OpenAPI `2.0`, `3.0.x`, and `3.1`.
- Loads specs from:
  - Local file (`.json`, `.yaml`, `.yml`)
  - URL list file (`.txt`, `.list`, `.urls`, `.csv`)
  - Remote URL
  - Burp Target context menu (`Send to OpenAPI Parser`)
  - Swagger UI pages (`/swagger/index.html`, `/swagger-ui/index.html`) with automatic spec URL fallback
- Displays operations in a searchable table:
  - Method, server, path, summary, operationId
  - Parameters and request body summary
  - Tags and server data
- Source isolation with `Source` dropdown (`All sources` + each loaded spec).
- Generates requests with schema-based sample values, including improved `allOf`/`oneOf`/`anyOf` and discriminator support.
- URL list loading quality-of-life:
  - Retry with backoff on temporary network failures
  - Live progress status + cancel button
  - Built-in error panel with failed URL list copy
- Reliable fetch guardrails:
  - Explicit response timeout + per-attempt deadline
  - Maximum spec size limit (`5 MiB`) via `Content-Length` and actual body length
- Remembers UI state between restarts (URL field, filter, selected source, selected server).
- Table actions (right-click context menu):
  - Send all visible requests to Repeater
  - Send selected requests to Repeater
  - Send selected requests to Intruder
  - Copy selected as cURL
  - Copy selected as Python `requests`
  - Export selected requests to UTF-8 TXT (`RAW HTTP + cURL + Python`)
  - Delete selected rows

## Requirements

- Java `17+`
- Maven `3.9+`
- Burp Suite `2025.1+`

## Build

```bash
mvn clean package
```

Output JAR:
`target/openapi-parser.jar`

## Install in Burp

1. Open `Burp -> Extender -> Extensions`.
2. Click `Add`.
3. Select extension type `Java`.
4. Choose `target/openapi-parser.jar`.
5. Verify extension output includes:
`[OpenAPI Parser] Loaded. Version=2.0.12, Author=MelForze`

## Quick Usage

1. Open the `OpenAPI Parser` tab in Burp.
2. Load one or more OpenAPI specs.
3. Pick a source, then pick a server from dropdowns.
4. Filter operations by method/path/tag/summary.
5. Use table right-click actions for Repeater/Intruder/export/delete.

## URL List File Format

- One URL per line is supported directly.
- Empty lines and lines starting with `#` are ignored.
- `http://` and `https://` are accepted.
- Bare host URLs are auto-normalized to `https://`:
  - `api.example.com/openapi.json` -> `https://api.example.com/openapi.json`
- CSV-like lines are accepted; the first URL-like token is used.
- If a URL points to Swagger UI HTML (`.../swagger/index.html`), the extension auto-discovers common spec endpoints (`/v3/api-docs`, `/openapi.json`, etc.).

Example:

```txt
# production
https://api.example.com/openapi.json
api2.example.com/swagger/v1/swagger.yaml

# csv-like rows
service-a,https://svc-a.example.com/v3/api-docs,team-red
```

## Notes

- Loading a new spec appends operations to the current dataset.
- Duplicate operations are skipped per source.
- Sample values are generated from `example`, `default`, and schema shape.
- Response payload decoding uses BOM/content-type/detector-based charset fallback to preserve non-English summaries/descriptions.

## License

MIT License. See [LICENSE](LICENSE).
