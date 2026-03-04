# OpenAPI Parser

`OpenAPI Parser` is a Burp Suite extension for authorized API security testing.
It parses OpenAPI/Swagger definitions and generates ready-to-use HTTP requests for manual assessment.

Use this project only in legal, authorized environments.

## Features

- Supports OpenAPI `2.0`, `3.0.x`, and `3.1`.
- Loads specs from:
  - Local file (`.json`, `.yaml`, `.yml`)
  - Remote URL
  - Burp Target context menu (`Send to OpenAPI Parser`)
- Displays operations in a searchable table:
  - Method, path, summary, operationId
  - Parameters and request body summary
  - Tags and server data
- Generates requests with schema-based sample values.
- Actions:
  - Send selected requests to Repeater
  - Send selected requests to Intruder
  - Copy as cURL
  - Copy as Python `requests`
  - Generate all visible requests into Repeater

## Requirements

- Java `17+`
- Maven `3.9+`
- Burp Suite `2025.1+`

## Build

```bash
mvn clean package
```

Output JAR:
`target/openapi-parser-2.0.9.jar`

## Install in Burp

1. Open `Burp -> Extender -> Extensions`.
2. Click `Add`.
3. Select extension type `Java`.
4. Choose `target/openapi-parser-2.0.9.jar`.
5. Verify extension output includes:
`[OpenAPI Parser] Loaded. Version=2.0.9, Author=MelForze`

## Quick Usage

1. Open the `OpenAPI Parser` tab in Burp.
2. Load one or more OpenAPI specs.
3. Pick a server from the dropdown.
4. Filter operations by method/path/tag/summary.
5. Select rows and run the needed action (Repeater/Intruder/cURL/Python).

## Notes

- Loading a new spec appends operations to the current dataset.
- Duplicate operations are skipped.
- Sample values are generated from `example`, `default`, and schema shape.

## License

MIT License. See [LICENSE](LICENSE).
