# OpenAPI Sampler

`OpenAPI Sampler` is a Burp Suite extension for authorized API security testing.  
It parses OpenAPI/Swagger definitions and generates ready-to-send HTTP requests for manual assessment workflows.

Use this project only in legal, authorized environments.

## Why OpenAPI Sampler

Compared to older OpenAPI parser extensions, this project focuses on:

- OpenAPI `3.1` support (plus `2.0` and `3.0.x`)
- Schema-aware request generation with better composed schema support:
  - `allOf` merge behavior
  - deterministic `oneOf` / `anyOf` branch selection
  - discriminator-aware payload examples
- Workflow actions for replay and export:
  - send to Repeater / Intruder
  - export as RAW HTTP + cURL + Python `requests`

## Features

- Load specs from:
  - local file (`.json`, `.yaml`, `.yml`)
  - URL list file (`.txt`, `.list`, `.urls`, `.csv`)
  - remote URL
  - Burp Target context menu (`Send to OpenAPI Sampler`)
  - Swagger UI pages (`/swagger/index.html`, `/swagger-ui/index.html`) with fallback discovery
- Source-aware operation view with server and search filters.
- Request preview pane and row-level context actions.
- URL-list loading quality-of-life:
  - retry with backoff on temporary failures
  - progress indicator and cancel button
  - failed-URL collection and copy helper
- Fetch guardrails:
  - response timeout and per-attempt deadline
  - maximum spec size checks (`5 MiB`)

## Requirements

- Burp Suite: `2025.1+`
- Java: `17+`
- Montoya API baseline: `2026.2` (build target)
- Maven: `3.9+` (for local build)

## Build

```bash
mvn clean package
```

Output JAR:
- `target/openapi-sampler.jar`

## Install in Burp

1. Open `Burp -> Extender -> Extensions`.
2. Click `Add`.
3. Select extension type `Java`.
4. Choose `target/openapi-sampler.jar`.
5. Verify extension output includes:
   - `[OpenAPI Sampler] Loaded. Version=2.0.13, Author=MelForze`

## Offline / Online Usage

- Offline usage is supported via local file loading.
- Bundled sample specs are provided in:
  - `samples/openapi-3.1-discriminator.yaml`
  - `samples/openapi-2.0-basic.yaml`
- Remote URL and URL-list fetch from internet/intranet endpoints requires network connectivity from Burp.

## Quick Usage

1. Open the `OpenAPI Sampler` tab in Burp.
2. Load one or more OpenAPI specs.
3. Pick source and server filters.
4. Filter operations by method/path/tag/summary.
5. Use context actions for replay/export operations.

## URL List File Format

- One URL per line.
- Empty lines and lines starting with `#` are ignored.
- Supports `http://` and `https://`.
- Bare host URLs are normalized to `https://`.
- CSV-like lines are supported; first URL-like token is used.

Example:

```txt
# production
https://api.example.com/openapi.json
api2.example.com/swagger/v1/swagger.yaml

# csv-like rows
service-a,https://svc-a.example.com/v3/api-docs,team-red
```

## Metadata Files

- BApp metadata: `bapp.manifest`
- Companion metadata for submission packaging: `extensions.xml`
- Icon asset used by companion metadata: `assets/openapi-sampler-icon.svg`

## License

MIT License. See [LICENSE](LICENSE).
