# BApp Submission Pack (OpenAPI Sampler)

This document tracks the repository assets and metadata prepared for BApp Store submission.

## Extension Identity

- Name: `OpenAPI Sampler`
- Version: `2.1.0`
- Author: `MelForze`
- Type: `Java (Montoya API)`
- Entry point: `burp.openapi.OpenApiSamplerExtension`

## Compatibility

- Minimum Burp version: `2025.1`
- Minimum Java version: `17`
- Montoya API baseline: `2026.2`

## Differentiators (for listing text)

- Supports OpenAPI `2.0`, `3.0.x`, and `3.1`
- Discriminator-aware example generation for composed schemas
- Auth profiles + template variables for repeatable request generation
- Batch runner with configurable rate/parallelism/retries
- Response validation against OpenAPI response schemas
- Export and replay workflows (RAW HTTP / cURL / Python requests)

## Repository Files Used for Submission

- Manifest: `bapp.manifest`
- Companion metadata: `extensions.xml`
- Icon: `assets/openapi-sampler-icon.svg`
- Samples (offline demo):
  - `samples/openapi-3.1-discriminator.yaml`
  - `samples/openapi-2.0-basic.yaml`

## Screenshot Checklist

Place screenshots in `docs/screenshots/` with these names:

1. `01-tab-overview.png`  
   Main tab loaded with parsed operations list.
2. `02-request-preview-and-actions.png`  
   Row selection, request preview, and context menu actions.
3. `03-batch-and-validation.png`  
   Batch runner/auth settings and validation workflow.

Recommended capture settings:

- Theme: Burp dark (or light) consistently across all screenshots
- Width: at least `1400px`
- PNG format
- No sensitive targets, tokens, or private hostnames

## Pre-Submit Verification

Run from repository root:

```bash
mvn -q test
mvn -q verify
mvn -q -DskipTests package
```

Produced artifact:

- `target/openapi-sampler.jar`
