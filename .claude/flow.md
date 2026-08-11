# ContentBridge — Project Flow

AEM-as-a-Cloud-Service module. Headless JSON export API: turns authored AEM pages into a JSON
contract an external (EDS/mobile/web) client renders. No page rendering (HTL) involved in the
API path — it walks the JCR content tree and serialises it.

Maintenance rule: **update this file whenever a major flow/endpoint/module changes** (new
endpoint, new schema version, new mapping type, changed OSGi config default, dispatcher routing
change). Keep it factual and current, not historical — historical reasoning goes in `decisions.md`.

## Modules (Maven reactor)

| Module | Purpose |
|---|---|
| `core` | Java bundle: servlet, exporter services, Sling Models, mapping/rule engine, form-schema engine |
| `ui.apps` | `/apps/contentbridge` content package (component defs, the `contentbridge/api` resource node) |
| `ui.apps.structure` | Immutable `/apps` repo structure package |
| `ui.config` | OSGi configs (runmode-specific) |
| `ui.content` | `/content` + `/content/dam` sample/demo content, `mode="merge"` filters |
| `ui.frontend` | Author-side clientlibs (webpack) |
| `all` | Aggregates the above into one deployable content package |
| `dispatcher` | AEM Dispatcher SDK config (farms, vhosts, filters, cache) |
| `it.tests` | Integration tests |

## Request flow (the one API endpoint)

```
GET /content/<site>/<path>/api/<name>.json?path=/content/<site>/us/en[&pretty=true][&schema=v2]
        │
        ▼  Dispatcher (dispatcher/src/conf.dispatcher.d/filters/contentbridge_filters.any)
        │  allows GET *.json under /content/*/api/* (rule 0100) and /apps/contentbridge/api (0110, author-only)
        ▼
  PageJsonServlet  (core/.../servlets/PageJsonServlet.java)
        │  bound by sling:resourceType "contentbridge/api" (NOT a fixed path — Adobe AEMaaCS guidance:
        │  path-bound servlets skip resource-resolution/ACL checks). The resource node lives at
        │  /apps/contentbridge/api; any page invoking that resourceType answers as the API.
        │
        │  1. reads required query param `path`
        │  2. exporter.isAllowed(path) — must sit under one of ContentBridgeConfiguration.allowedRoots
        │     (default ["/content"]) or → 403
        │  3. opens ResourceResolver as system user "contentbridge-reader" (repoinit grants jcr:read on
        │     /content) so one endpoint can serve every site regardless of caller ACLs.
        │     Falls back to the caller's own resolver + WARN log if the service user can't see the page
        │     (keeps a misconfigured service user from taking author down; on publish there is no
        │     fallback since the caller is anonymous)
        │  4. resolves the Page via PageManager, 404 if none
        │  5. exporter.export(page, ExportOptions) → Map<String,Object>
        │  6. Cache-Control: max-age=<cacheMaxAge> (default 300s) or no-store if 0
        │  7. Jackson-serialises to JSON (pretty if ?pretty=true); RuntimeException/IOException →
        │     JSON error body {status, error}, never an HTML error page
        ▼
  PageJsonExporterImpl  (core/.../services/impl/PageJsonExporterImpl.java)
        │  - builds page meta (title, template, hostname author→publish segment swap, navigation
        │    menu from navPageNames fragment)
        │  - walks child pages up to maxDepth to collect "screens", skipping excludedPageNames and
        │    pages flagged excludeFromApp
        │  - ComponentTreeWalker walks each screen's component tree, MAX_COMPONENT_DEPTH=12 guard
        │  - ComponentMappings/ComponentMapping (rule engine, driven by
        │    core/src/main/resources/contentbridge/mappings/*.json) maps each Sling resource type to
        │    an output component: either flat "extras passthrough" or fixed-skeleton mode (skeleton
        │    key set always present, nullable fields emit "" not null, unconsumed CRX props pass
        │    through as extras)
        │  - forms: FormSchemaServiceImpl reads core/src/main/resources/contentbridge/forms/*.json
        │    to describe authored form components (FormBlockBuilder + FieldRule + FormFieldFactory)
        │  - products: ProductExporter reads Content Fragments under
        │    /content/dam/<site>/<productsFolderName> (default "products") OR, if requested via
        │    ?gqlConfig=&gqlQuery= (or the site's placeholders sheet), GraphQlProductSource dispatches
        │    a persisted GraphQL query in-JVM via SlingRequestProcessor (optional ref — falls back to
        │    Content Fragments if unbound). maxProducts caps the response (default 200, truncates +
        │    warns)
        │  - StyleResolver resolves authored style tokens (sec-spacing, bg-*) → concrete
        │    {property:value} pairs for v2 schema only, via ContentBridgeConfiguration.styleTokens
        ▼
  JSON response, schema v1 (flat, default) or v2 (?schema=v2 — layout containers preserved as
  "panel" components with nested "content" arrays; versioned rather than replacing v1 so existing
  clients keep working)
```

## Query parameters (PageJsonServlet)

| Param | Required | Meaning |
|---|---|---|
| `path` | yes | JCR path of the page to export, must be under an allowed root |
| `pretty` | no | `true` → indented JSON |
| `schema` | no | `v1` (default, flat) or `v2` (hierarchical panels) |
| `productsPath` | no | override where product Content Fragments are read from |
| `gqlConfig` | no | named GraphQL endpoint config, e.g. `dsn-eds-configuration` |
| `gqlQuery` | no | persisted GraphQL query name, e.g. `productListByPathBinji` |

## API endpoints (effective, by environment)

Servlet is resourceType-bound, not path-bound — the actual URL is `/content/<site>/.../api/<name>.json`
wherever a resource with `sling:resourceType="contentbridge/api"` exists (conventionally
`/apps/contentbridge/api`, consumed via `/content/<site>/api`). One code path serves every
environment; only the host differs.

| Env | Host pattern | Notes |
|---|---|---|
| Author (dev) | `https://author-p<programId>-e<envId>.adobeaemcloud.com` | env ID assigned by Cloud Manager per environment, not derivable from another env's URL — get it from Cloud Manager → Environments |
| Publish (dev) | `https://publish-p<programId>-e<envId>.adobeaemcloud.com` | same envId as its paired author |
| UAT / stage / prod | same pattern, different `p<programId>-e<envId>` pair per Cloud Manager environment | look up per environment |

Example: `GET https://publish-pXXXXX-eYYYYYY.adobeaemcloud.com/content/contentbridge/api/getPageDetails.json?path=/content/contentbridge/us/en`

## Dispatcher routing (`dispatcher/src/conf.dispatcher.d/`)

- **Farm**: `available_farms/contentbridge.farm` (+ identical copy in `enabled_farms/`, plain file
  copy not symlink — Windows-checkout safety)
- **Filters**: `filters/contentbridge_filters.any` — `/0100` allows GET `*.json` under
  `/content/*/api/*`; `/0110` allows GET `/apps/contentbridge/api.json` (author-only convenience,
  publish denies anonymous `/apps` regardless); `/0120` allows DAM image extensions under
  `/content/dam/*`
- **Cache**: `cache/contentbridge_cache.any` — caches both API paths above; invalidates on any
  change under `/content/*` (not just this repo's demo content — API serves any site)
- **VHost**: `conf.d/available_vhosts/contentbridge.vhost` (+ `enabled_vhosts/` copy) — CORS
  `LocationMatch` covers both API paths; `ServerAlias` includes `*.adobeaemcloud.net` /
  `*.adobeaemcloud.com` for Cloud Manager default domains

## Key OSGi config (`ContentBridgeConfiguration`, PID via `ui.config`)

`allowedRoots` (default `/content`), `maxDepth` (3), `navPageNames`, `excludedPageNames`,
`includeHomeScreen` (true), `authorHostSegment`/`publishHostSegment` (author↔publish host
rewrite), `productsFolderName` (`products`), `maxProducts` (200), `productFieldAliases`,
`styleTokens`, `cacheMaxAge` (300s).

## Mapping-driven component/form catalogue

- `core/src/main/resources/contentbridge/mappings/*.json` — one file per component type
  (title, button, hero→banner, image, card, text, divider, map, offer, productImage,
  productTitle, timeSlotPicker, userDetails, userLogin, list, contentCards) + `index.json`
- `core/src/main/resources/contentbridge/forms/*.json` — one file per form type (checkout,
  create-account, loan-application-form, etc.) + `index.json`
- Adding a new component/form type = add a mapping JSON file + (if needed) a Sling Model in
  `core/.../models/impl/`. No servlet/exporter code change required for a straightforward mapping.
- Rule-file field operators (`FieldRule.java`): `const`, `extract` (CSS selector over rich text),
  `first` (first non-blank of several candidate props), `from` (straight copy), `fromParent`
  (reads the enclosing section instead of the component). Independent of the operator, an optional
  `map` value-lookup translates the resolved value (e.g. title's `titleType` h1-h6 → size token
  xlarge/large/medium/small) — unmapped values pass through unchanged. `type: "path"` strips
  scheme+host off an absolute http(s) link, keeping only path/query/fragment — used on every
  link-carrying field (`button.linkTo`, `card.link`, `hero.linkTo`, `image.linkTo`,
  `offer.linkTo`) so responses never leak the author/preview host a link happened to be authored
  against.
