# ContentBridge — Agent Instructions

ContentBridge is an AEM as a Cloud Service project that exports authored pages as a stable JSON contract via a single GET endpoint. Built on AEM Project Archetype 57, AEM SDK API `2026.6.26908`, Core Components `2.31.2`, Java 11.

---

## Build & Test Commands

```bash
# Unit tests only (fast, no AEM required)
mvn test -pl core

# Full build + deploy to local AEM author (localhost:6502)
mvn clean install -PautoInstallSinglePackage

# Deploy to a non-default port
mvn clean install -PautoInstallSinglePackage -Daem.port=4502

# Deploy core bundle only (faster iteration)
mvn clean install -PautoInstallBundle -pl core

# Build without deploying
mvn clean install
```

> Integration tests live in `it.tests/` and only run under `-PintegrationTests`. Do not run them unless explicitly asked.

---

## Project Structure

| Module | Purpose |
|---|---|
| `core/` | All Java: services, models, servlet, OSGi configs, bundled form schemas |
| `ui.apps/` | Component definitions, servlet node (`/apps/contentbridge/api`) |
| `ui.content/` | Sample content, the `/content/<site>/api/getPageDetails` endpoint node |
| `ui.config/` | OSGi configs, repoinit (system user + ACLs) |
| `all/` | Aggregates everything into one deployable package |

All Java lives under `com.dept.contentbridge.core.{services,models,schema,servlets}` — public APIs in the parent package, implementations in `impl/`.

---

## Key Services

See [README.md](README.md) for the full API contract. Core services:

| Class | Role |
|---|---|
| `PageJsonExporterImpl` | Top-level orchestrator — builds the full JSON envelope |
| `ComponentTreeWalker` | Turns a page's JCR tree into the flat `content[]` array |
| `NavigationExtractor` | Parses authored HTML properties with jsoup to build `menu` |
| `ProductExporter` | Reads product Content Fragments, projects onto fixed key set |
| `SiteHostResolver` | Per-site hostname from `placeholders` page; rewrites author → publish |
| `FormSchemaServiceImpl` | Loads/caches form schemas from bundled JSON at activation |
| `UrlServiceImpl` | Externalises DAM/repo paths to absolute publish URLs |
| `ContentPaths` | Rewrites `/content/` paths and rich-text `href`/`src` attributes |

---

## OSGi Conventions

- Configuration interfaces use `@ObjectClassDefinition` on a **separate `@interface`**, not on the `@Component` class.
- `@Activate` method receives the typed config; arrays are `.clone()`d; derived objects (`ComponentTreeWalker`, `SiteHostResolver`, `ProductExporter`) are instantiated **once at activation**, not per request.
- The system user `contentbridge-reader` is mapped via sub-service name `"contentbridge-reader"`. Bundle symbolic name is `contentbridge.core` — verify at `/system/console/bundles` if you get a `LoginException`.
- jsoup is **embedded** via `-conditionalpackage: org.jsoup.*` in bnd — do not change this to an OSGi import; AEM's internal jsoup is undocumented and may differ across SDK versions.

---

## Code Conventions

- **Insertion order is the contract** — use `LinkedHashMap` for any map that ends up in JSON output.
- **Blank-filtering output** — `ContentBuilder.put(key, value)` silently skips blank strings. This is intentional; do not add null checks around it.
- **Static factory methods** on value objects (`ExportOptions.defaults()`, `ExportOptions.of(...)`) — no public constructors.
- **`@ProviderType`** on service interfaces the project owns; **`@ConsumerType`** on interfaces third parties implement (`ContentBridgeComponent`).
- Sling Models: all component models are in `models/impl/`, adapt `Resource.class`, and implement `ContentBridgeComponent`.

---

## Adding a New Component

1. Create a `@Model` class in `core/.../models/impl/` extending `AbstractComponentModel`.
2. Override `getContent()` returning a `ContentBuilder`-populated map.
3. Register it for the resource type via `@Model(resourceType = SchemaConstants.RT_*)`.
4. No changes to the servlet or exporter — `ComponentTreeWalker` picks it up automatically.

## Adding a Form Schema

1. Add the JSON schema file to `core/src/main/resources/contentbridge/forms/<name>.json`.
2. Add `<name>` to `core/src/main/resources/contentbridge/forms/index.json`.
3. `FormSchemaServiceTest` will fail if the index and the directory are out of sync — this is intentional.

---

## Critical Pitfalls

| Pitfall | Detail |
|---|---|
| System user at wrong path | `contentbridge-reader` **must** be under `system/cq:services/`. Using `path` instead of `forced path` in repoinit leaves an existing misplaced user in place — use `forced path`. |
| `/apps` endpoint on publish | `/apps` is not readable by anonymous on publish. The real endpoint must be under `/content` (in `ui.content`). |
| `allowedRoots` is a security boundary | The service user bypasses per-user ACLs. `allowedRoots` is the only restriction on what paths the API exposes. |
| Bundle symbolic name mismatch | The left-hand side of the `ServiceUserMapperImpl.amended` mapping must match the bundle's symbolic name (`contentbridge.core`). A mismatch causes a `LoginException` that is not obvious from the log. |
| Sling Models not scanned | If all responses return `"content": []`, check `/system/console/status-slingmodels` — the bnd `ModelsScannerPlugin` must have run and the `Sling-Model-Classes` header must be present in the bundle. |
| `FormSchemaService` uses `index.json` | `Bundle.findEntries()` does not work in test classloaders. The index file is required; do not remove it or replace it with classpath scanning. |

---

## Test Utilities

`ContentBridgeTestContext` (in `core/src/test/`) provides a pre-configured `AemContext` (JCR_MOCK) with `content.json` loaded and `UrlServiceImpl`, `FormSchemaServiceImpl`, and `PageJsonExporterImpl` activated with test config. Use this as the base for new service tests.
