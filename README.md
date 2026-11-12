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
target/openapi-sampler-2.2.1.jar
```

## Install

1. Open `Extensions > Installed > Add` in Burp.
2. Select extension type `Java`.
3. Choose `target/openapi-sampler-2.2.1.jar`.
4. Check the extension output for:

```text
[OpenAPI Sampler] Loaded. Version=2.2.1, Author=MelForze
```

## Usage

1. Open the `OpenAPI Sampler` tab.
2. Load a spec from a file, URL, URL list, Swagger UI page, or Burp Target context menu.
3. Select source/server filters if needed.
4. Select operations in the table.
5. Use right-click actions to send, export, delete, or change server for selected operations.

URL fetches verify upstream TLS certificates. Specification bodies and local spec files are limited to 5 MiB.
The Target context menu checks JSON/YAML Content-Types (including `+json`/`+yaml`) and inspects at most
16 KiB of the response before offering the action; full decoding and parsing run in the background after selection.

## Scanner integration

Active scan submissions use the public `doActiveScan()` API. Burp can place these requests in
**Extension driven active audit**, which is separate from the **Active scans** task created by
native UI actions. The public API does not let this extension select an existing Dashboard task
or configure the extension-driven task's scan checks. The extension does not create a separate
Montoya active `Audit` for every batch, change target scope, or overwrite Scanner settings.
Burp handles any confirmation for requests outside the active scanning scope.

For maximum coverage, edit the **Extension driven active audit** task's scan configuration in Burp:

1. Enable all desired built-in issues in **Auditing > Scan checks**, including Passive,
   Light active, Medium active, Intrusive active, and JavaScript analysis. Check individual issue
   selections as well as the scan type filters.
2. Apply **Audit coverage - maximum** from the configuration library. This expands payload and
   insertion-point coverage; it is separate from selecting the scan check types.
3. If needed, enable the installed BChecks and extension scan checks separately.

These settings increase scan time and resource usage. Intrusive checks can alter application data.
See PortSwigger's [audit settings](https://portswigger.net/burp/documentation/scanner/scan-configurations/audit-settings)
and [built-in configurations](https://portswigger.net/burp/documentation/scanner/scan-configurations/burp-scanner-built-in-configs).
To add requests to a particular existing task, use Burp's native **Scan > Add to task** action
from a tool such as Repeater. The extension cannot perform that task selection via the public API.

For programmatic scan creation with a specific scan configuration, PortSwigger documents the
Desktop REST API as a separate supported integration path. It requires an enabled REST service
and an API key, and is not implemented by this extension. It must not be confused with submitting
to a particular existing Dashboard task. See the [PortSwigger API clarification](https://github.com/PortSwigger/burp-extensions-montoya-api/issues/49)
and [Desktop REST API settings](https://portswigger.net/burp/documentation/desktop/settings/suite/rest-api).

Montoya remains the main API. A small legacy API bridge follows PortSwigger's official
[multi-API example](https://github.com/PortSwigger/burp-extensions-montoya-api-examples/tree/main/multiapi).
Both API dependencies are provided by Burp and are excluded from the extension JAR.
Passive scanning continues to use a separate Montoya audit. Unloading the extension stops new
submissions and leaves existing scans and results intact.

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
