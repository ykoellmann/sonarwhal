# Sonarwhale

A JetBrains IDE plugin for testing your API endpoints during development — without leaving the editor.

## What is Sonarwhale?

Sonarwhale is a development-time HTTP testing tool that sits inside your IDE. It connects to your project's OpenAPI spec, discovers your endpoints automatically, and lets you fire requests right from the editor. No tab switching, no copy-pasting routes, no keeping a separate tool in sync with your code.

It's not trying to replace Postman for managing large API collections or collaborating across teams. Sonarwhale is built for the developer who just added a new endpoint and wants to hit it *right now* — without setting anything up first.

## Why?

You write a new controller method, start the dev server, and want to test it. Today that means: open Postman, create a new request, type the URL, add headers, paste a body, maybe set up auth. By the time you've done all that, you've lost your flow.

Sonarwhale shortens that loop. Point it at your dev server once, and it reads your OpenAPI spec to discover all endpoints with their parameters, schemas, and auth requirements. Click an endpoint in the tool window or hit the gutter icon next to the code — the request is pre-filled and ready to send.

When your API changes, Sonarwhale picks it up on the next refresh and shows you what's different: new endpoints, modified signatures, removed routes. You always see the current state of your API without doing anything.

## Features

- **Automatic endpoint discovery** from your project's OpenAPI spec
- **Three source modes**: live dev server URL (with auto-discovery of common OpenAPI paths), local spec file (re-read on build), or static import
- **Pre-filled requests** — parameters, body schemas, and auth are populated from the spec
- **Gutter icons** next to endpoint definitions for one-click testing
- **Environment switching** — toggle between dev, staging, and other environments
- **Endpoint diff tracking** — see what changed between refreshes (added, modified, removed)
- **Fallback caching** — if the dev server is down, Sonarwhale keeps working from the last known state
- **Postman-compatible import/export** for when you do need to share collections
- **Local state** stored in `.idea/` — no account, no cloud, no setup

## How It Works

You configure an environment (e.g. `dev` at `localhost:5000`), and Sonarwhale fetches the OpenAPI spec, parses it, and populates the tool window. Refreshes happen automatically on build events, file saves, or at a configurable interval.

For code navigation — jump to definition, gutter icons — Sonarwhale uses a lightweight PSI bridge that matches OpenAPI routes back to their source locations. This is purely a navigation aid; all endpoint data comes from the OpenAPI spec.

## Framework Support

Sonarwhale works with any framework that generates an OpenAPI spec. Auto-discovery probes common paths out of the box:

| Framework | OpenAPI Path |
|---|---|
| ASP.NET Core (Swashbuckle) | `/swagger/v1/swagger.json` |
| ASP.NET Core (Microsoft.OpenApi) | `/openapi/v1.json` |
| FastAPI | `/openapi.json` |
| Spring Boot (springdoc) | `/v3/api-docs` |
| Express + swagger-jsdoc | `/api-docs` |

Custom paths can be configured per environment.

## Mascot

Roux the Narwhal — because narwhals have the best echolocation in nature, and Sonarwhale finds your endpoints like sonar finds submarines.

## Status

Early development. Currently targeting JetBrains Rider 2025.3+ with ASP.NET Core as the first fully supported stack.

## License

TBD