# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read these first

- [AGENTS.md](AGENTS.md) — build commands, module map, OSGi conventions, code conventions, common pitfalls. **Treat this as the primary source.**
- [README.md](README.md) — user-facing API contract (endpoint, params, JSON response shape).
- [.claude/flow.md](.claude/flow.md) — end-to-end request flow (dispatcher → servlet → exporter → JSON envelope).
- [.claude/decisions.md](.claude/decisions.md) — append-only log of non-obvious design decisions. **Add an entry when you make a schema/contract change, reject an approach, or change an OSGi default.**

## What this project is

ContentBridge is an AEM as a Cloud Service project with one job: expose authored pages as a stable JSON contract via a single GET endpoint (`/content/<site>/api/getPageDetails.json?path=...`). The API path never renders HTL — it walks the JCR tree and serialises it. Built on AEM Project Archetype 57, Java 11, AEM SDK `2026.6.26908`, Core Components `2.31.2`.

## Common commands

```bash
mvn test -pl core                                       # unit tests (fast, no AEM)
mvn test -pl core -Dtest=PageJsonExporterImplTest       # single test class
mvn clean install -PautoInstallSinglePackage            # full build + deploy to author on :6502
mvn clean install -PautoInstallSinglePackage -Daem.port=4502   # different port
mvn clean install -PautoInstallBundle -pl core          # deploy core bundle only (iterate fast)
mvn clean install                                       # build without deploying
```

Integration tests in `it.tests/` only run under `-PintegrationTests`. Don't run them unless asked.

## Architecture — the "big picture"

The request path is **`PageJsonServlet` → `PageJsonExporterImpl` → `ComponentTreeWalker` → Sling Models → JSON envelope**. Understanding these boundaries is what makes edits safe:

1. **Servlet binding is by resource type, not path.** `PageJsonServlet` is bound to `sling:resourceType="contentbridge/api"`. Adobe advises against `@SlingServletPaths` on AEMaaCS — path binding skips resource resolution and ACL checks. The real endpoint lives at `/content/<site>/api/getPageDetails` (in `ui.content`); the `/apps/contentbridge/api` node exists for author-side only and returns 404 on publish (`/apps` isn't anonymous-readable there).

2. **All content is read as `contentbridge-reader` (system user), not the caller.** That's what lets one endpoint serve every site under `/content`. The consequence: **`ContentBridgeConfiguration.allowedRoots` is the only real access boundary** — treat any change to it as a security change, and restrict at the dispatcher on publish.

3. **`ComponentTreeWalker` is auto-discovering.** It walks the JCR tree of a page and adapts each resource to a Sling Model implementing `ContentBridgeComponent`. To add a new component to the JSON output: create a `@Model` under `models/impl/` extending `AbstractComponentModel`, register it with `@Model(resourceType = SchemaConstants.RT_*)`, and override `getContent()` — **no servlet or exporter changes needed**.

4. **Services are activated once, not per request.** `ComponentTreeWalker`, `SiteHostResolver`, `ProductExporter` etc. are constructed in `@Activate` on `PageJsonExporterImpl` with the typed OSGi config. Don't move construction into request-handling paths.

5. **JSON output uses `ContentBuilder` + `LinkedHashMap`** everywhere. Insertion order is part of the contract. `ContentBuilder.put(key, value)` silently drops blank strings — this is intentional; do not add null-guards around callers.

6. **Schema versioning is opt-in.** v1 (flat) is default; `?schema=v2` returns hierarchical panels. New schema versions must be additive query params, never a breaking replacement of v1.

7. **jsoup is embedded via bnd `-conditionalpackage: org.jsoup.*`.** Do not change it to an OSGi import — AEM's internal jsoup version is undocumented and differs across SDK versions.

8. **Form schemas ship as JSON on the classpath.** `FormSchemaServiceImpl` loads `core/src/main/resources/contentbridge/forms/*.json` at activation, driven by `index.json` in that directory. `Bundle.findEntries()` doesn't work in test classloaders, so the index file is mandatory — `FormSchemaServiceTest` fails if the index and directory drift apart.

## Package layout inside `core`

Public API in `com.dept.contentbridge.core.{services,models,schema,servlets}`, implementations in `impl/` subpackages. `@ProviderType` on interfaces the project owns; `@ConsumerType` on `ContentBridgeComponent` (extension points).

OSGi configs use a **separate `@interface` with `@ObjectClassDefinition`**, not annotations on the `@Component` class. `@Activate` receives that typed config; array fields are `.clone()`d before storing.

## Testing

`ContentBridgeTestContext` (in `core/src/test/`) is the base for service tests — provides a pre-configured `AemContext` (JCR_MOCK) with `content.json` loaded and `UrlServiceImpl`, `FormSchemaServiceImpl`, `PageJsonExporterImpl` already activated with test config.

## When responses come back with `"content": []`

Almost always the Sling Models weren't scanned. Check `/system/console/status-slingmodels` — the bnd `ModelsScannerPlugin` must have run and the bundle must carry the `Sling-Model-Classes` header. See AGENTS.md → *Critical Pitfalls* for the full list (bundle symbolic name mismatches, misplaced system user, `allowedRoots` being the security boundary).
