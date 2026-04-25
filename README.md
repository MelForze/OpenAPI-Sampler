# OpenAPI Sampler

Burp Suite extension for turning OpenAPI/Swagger specs into ready-to-send HTTP requests.

Use it only in legal, authorized testing environments.

## Features

- Loads OpenAPI `2.0`, `3.0.x`, and `3.1` specs from local files, URLs, URL lists, Swagger UI pages, and Burp Target context menu.
- Generates schema-aware sample requests, including `allOf`, `oneOf`, `anyOf`, and discriminator-aware payloads.
- Shows request preview and expected response preview from OpenAPI examples/schemas.
- Sends generated requests to Repeater, Intruder, Active scan, and Passive scan.
- Exports selected requests as RAW HTTP, cURL, and Python `requests`.
- Supports auth profiles: Bearer, OAuth2 Bearer, Basic, API key header, and API key query.
- Lets you override the server for one or more selected operations.
- Loads URL lists incrementally, so operations appear as each spec is fetched.

## Requirements

- Burp Suite `2026.2+`
- Java `17+`
- Maven `3.9+` for local builds

## Build

```bash
mvn clean package
```

The JAR is created at:

```text
target/openapi-sampler-2.2.0.jar
```

## Install

1. Open `Extensions > Installed > Add` in Burp.
2. Select extension type `Java`.
3. Choose `target/openapi-sampler-2.2.0.jar`.
4. Check the extension output for:

```text
[OpenAPI Sampler] Loaded. Version=2.2.0, Author=MelForze
```

## Usage

1. Open the `OpenAPI Sampler` tab.
2. Load a spec from a file, URL, URL list, Swagger UI page, or Burp Target context menu.
3. Select source/server filters if needed.
4. Select operations in the table.
5. Use right-click actions to send, export, delete, or change server for selected operations.

## Auth

Auth is optional and applied only to generated requests:

- `Bearer` / `OAuth2 Bearer`: enter `Token`.
- `Basic`: enter `Username` and `Password`.
- `API Key (Header)`: enter `Header name` and `Value`.
- `API Key (Query)`: enter `Query name` and `Value`.

## URL List Format

- One URL per line.
- Empty lines and lines starting with `#` are ignored.
- `http://` and `https://` are supported.
- Bare hosts are normalized to `https://`.
- CSV-like rows are supported; the first URL-like token is used.

```txt
# production
https://api.example.com/openapi.json
api2.example.com/swagger/v1/swagger.yaml

# csv-like row
service-a,https://svc-a.example.com/v3/api-docs,team-red
```

## Samples

- `samples/openapi-3.1-discriminator.yaml`
- `samples/openapi-2.0-basic.yaml`

## License

MIT License. See [LICENSE](LICENSE).
