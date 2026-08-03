# ContentBridge

An AEM as a Cloud Service project whose only job is to publish authored pages as a stable JSON
contract. Authors work in the normal AEM page editor; clients read a single GET endpoint.

```
GET /bin/contentbridge/pages?path=/content/contentbridge/us/en
```

It works against **any site under `/content`** — no per-project configuration, no need to know your
resource types.

Built on **AEM Project Archetype 57** conventions — AEM SDK API `2026.6.26908`, Core Components
`2.31.2`, plain-JavaScript front end, Java 11.

## Quick start

Requires JDK 11+, Maven 3.8.1+, and a running AEM as a Cloud Service SDK author instance.

```bash
git clone https://github.com/Milandevnath/content-bridge.git
cd content-bridge
mvn clean install -PautoInstallSinglePackage
```

The build targets `localhost:5402` by default. For a different port:

```bash
mvn clean install -PautoInstallSinglePackage -Daem.port=4502
```

Then:

```bash
curl -u admin:admin \
  'http://localhost:5402/bin/contentbridge/pages?path=/content/contentbridge/us/en&pretty=true'
```

If every screen comes back with `"content": []`, check
`/system/console/status-slingmodels` — see the note under *Configuration*.

---

## The API

| | |
|---|---|
| Path | `/bin/contentbridge/pages` |
| Method | `GET` |
| `path` | **required.** Repository path of a page. Must sit under an allowed root. |
| `pretty` | optional. `true` indents the response. |
| Response | `application/json;charset=UTF-8` |

`path` behaves in two ways, which is usually what you want:

- point it at the **app root** (the page carrying an `appId`) and you get every screen;
- point it at a **sub page** and you get the same envelope with a single screen in `screens`.

Either way `meta`, `branding`, `integrations`, `profileViewer` and `products` are read from the app
root, so a client that deep-links into one screen still receives the theme and navigation it needs.

### Status codes

| Code | When |
|---|---|
| `200` | Page found and exported. `Cache-Control: max-age=<configured>`. |
| `400` | `path` missing. |
| `403` | `path` is outside `allowedRoots`. |
| `404` | No page at `path`. |
| `500` | Serialisation failed. |

Errors return JSON (`{"status":403,"error":"..."}`) so clients never have to parse an HTML error page.

### Example

```bash
curl -u admin:admin \
  'http://localhost:5402/bin/contentbridge/pages?path=/content/contentbridge/us/en&pretty=true'
```

---

## Response shape

```jsonc
{
  "id": "ContentBridge: sample-app",
  "meta": {
    "id": "ContentBridge: sample-app",
    "type": "mobile-v2",
    "title": "ContentBridge Sample App",
    "useCart": true,
    "navigation": {
      "menu": ["Home", "subpage-1", "subpage-2", "Subpage3"],
      "loginScreen": "login",
      "profileScreen": "user-details",
      "authorizedMenu": [],
      "registrationScreen": "registration"
    }
  },
  "screens": {
    "Home": {
      "meta": { "name": "Home", "title": "Home", "icon": "home" },
      "content": [
        { "meta": { "id": "9199a89b", "component": "banner" }, "content": { /* ... */ } }
      ]
    }
  },
  "branding":      { /* colour palette + logos */ },
  "integrations":  { /* free-form JSON */ },
  "profileViewer": { /* free-form JSON */ },
  "products":      [ /* free-form JSON array */ ]
}
```

Two invariants worth relying on:

- **`meta.navigation.menu` and the keys of `screens` are always the same list, in the same order.**
- **Blank values are omitted, never emitted as `""`.** `divider` is the extreme case: its `content`
  is an empty object by design.

`meta.id` for each component is `Integer.toHexString(path.hashCode())` — stable across exports and
rebuilds, changing only if the component is moved.

### Components

| `component` | Resource type | Payload keys |
|---|---|---|
| `banner` | `contentbridge/components/banner` | `image`, `title`, `titleVariant`, `subtitle`, `buttonLabel`*, `linkTo`*, `buttonIcon`*, `showButton`, `horizontalAlignment`, `verticalAlignment`, `contentAlignment`, `isSubtitleRichText` |
| `title` | `contentbridge/components/title` | `text`, `size`, `textAlignment` |
| `htmlText` | `contentbridge/components/htmlText` | `text`, `textAlignment` |
| `card` | `contentbridge/components/card` | `image`, `link`, `title`, `titleVariant`, `textTransform`, `isSubtitleRichText`, `subtitle`, `buttonLabel`, `cardType` |
| `divider` | `contentbridge/components/divider` | *(none)* |
| `image` | `contentbridge/components/image` | `image`, `link`, `alt` |

\* only present when `showButton` is on.

Booleans inside `content` are emitted as **strings** (`"true"` / `"false"`) to match the published
contract. Booleans in `meta` (e.g. `useCart`) are real JSON booleans.

### Any site, any component

The exporter works against an arbitrary site under `/content` — it does not need to know your
resource types. Resolution runs in two tiers:

1. **A registered Sling Model wins.** Implement `ContentBridgeComponent` for a resource type and you
   control that component's payload exactly. Use this for components whose contract matters.
2. **Everything else is projected generically.** The component name is the `model` property when
   present, otherwise the last segment of the resource type — `acme/components/content/teaser`
   becomes `teaser`. The payload is every property that is not `jcr:`/`sling:`/`cq:`/`granite:`/
   `oak:`/`rep:` bookkeeping. Values starting `/content/dam/` are externalised.

An unrecognised component therefore degrades to a faithful mechanical projection rather than
disappearing from the response.

**Containers are walked through, not exported.** A node is treated as structural when it is a known
layout type (`responsivegrid`, `container`, `parsys`, `iparsys`, `root`, `section`, `columns`), when
it has children but no `sling:resourceType`, or when it has children but no authored properties of
its own. That last rule is what identifies a wrapper without hard-coding its resource type. The
component list is always flat, however deeply the source nests.

Components are read from the first of `root/container`, `root`, `par`, `content/par` that exists,
falling back to `jcr:content`.

### AEM Edge Delivery (Franklin) content

The exporter reads two authoring models through the same contract.

Edge Delivery does not give components their own resource types — everything is a generic
`core/franklin/components/block/v1/block` (or `.../block/item`, `text`, `title`), and the component
identity lives in a **`model`** property: `hero`, `card`, `teaser`, `newsletter`, `separator`,
`accordion-item`, `video`, `search`, and so on. `FranklinComponentModel` handles the whole
vocabulary, so a new Edge Delivery block needs no code here.

Two rules make this work:

- **Structural nodes are flattened, not exported.** `root`, `section`, `columns` and the untyped
  `row1`/`col1` wrappers are descended into. So are *container* blocks such as `cards` and
  `accordion`, which are recognised by having children — otherwise they would export as an empty
  component and swallow the items beneath them. The result is the same flat `content[]` array that
  hand-authored pages produce. Section styling (`sec-spacing`, `sec-custom-styles`) is dropped.
- **`modelFields` decides what counts as content.** Edge Delivery records the authored field names
  on each node, and that list also fixes field order in the output. Anything present but missing
  from `modelFields` is appended rather than dropped. Where the property is absent, everything that
  is not `jcr:`/`sling:`/`cq:`/`model`/`modelFields`/`name`/`filter` is treated as content.

Values beginning `/content/dam/` are externalised; page links are left as authored. The check is on
the value, not the property name, because Edge Delivery uses `image`, `imageRef` and `fileReference`
interchangeably.

### Adding a component

No change to the exporter is needed. Write a Sling Model that implements `ContentBridgeComponent`
and declares its resource type:

```java
@Model(adaptables = Resource.class,
       adapters = {ContentBridgeComponent.class, AccordionModel.class},
       resourceType = "contentbridge/components/accordion",
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class AccordionModel extends AbstractComponentModel {

    @Override
    public String getComponentName() {
        return "accordion";
    }

    @Override
    protected void buildContent(final ContentBuilder builder) {
        builder.put("heading", prop("jcr:title"))
               .putFlag("startsOpen", bool("startsOpen", false));
    }
}
```

The exporter adapts each child resource to `ContentBridgeComponent`; anything it cannot adapt is
logged at DEBUG and skipped, so an unmapped component degrades gracefully instead of breaking the
whole response.

---

## Authoring

Everything the envelope needs lives in **Page Properties**, in four added tabs.

| Tab | Sets |
|---|---|
| ContentBridge App | `appId` (**this marks the page as the app root**), `appTitle`, `appType`, `useCart`, login / registration / profile screens, `authorizedMenu` |
| ContentBridge Screen | `screenName` (key used in `screens` and `menu`), `screenIcon`, `excludeFromApp` |
| ContentBridge Branding | Logos, variant, roundness, and the full light/dark colour palette |
| ContentBridge Extras | Raw JSON for `integrations`, `profileViewer`, `products` |

Invalid JSON in the Extras tab is logged and exported as an empty object/array — a typo cannot take
the endpoint down.

---

## Build and deploy

The `aem.port` property defaults to **5402**, so the plain commands target that instance.

```bash
# Full build + deploy to the local author instance on :5402
mvn clean install -PautoInstallSinglePackage

# Java only, hot-swapped into the running OSGi container
mvn clean install -PautoInstallBundle -pl core

# Content only
mvn clean install -PautoInstallPackage -pl ui.content

# Publish instance (defaults to :5403)
mvn clean install -PautoInstallSinglePackagePublish
```

Override the target without editing the POM:

```bash
mvn clean install -PautoInstallSinglePackage -Daem.port=4502
```

Unit tests run in the normal build. Integration tests hit a live instance:

```bash
mvn verify -PintegrationTests
```

### After the first deploy

1. Author: <http://localhost:5402/sites.html/content/contentbridge/us/en>
2. API: <http://localhost:5402/bin/contentbridge/pages?path=/content/contentbridge/us/en&pretty=true>

The sample content references DAM assets under `/content/dam/contentbridge/…` that are **not**
shipped in this package. Upload your own or repoint the components — the export works either way,
those keys are simply omitted while the references are unresolved.

---

## Modules

| Module | Purpose |
|---|---|
| `core` | Sling Models, `PageJsonExporter`, `UrlService`, the servlet |
| `ui.apps` | Components, dialogs, page-properties extensions, client library |
| `ui.apps.structure` | Declares the JCR roots so deployment fails fast on a missing parent |
| `ui.config` | Run-mode aware OSGi configuration |
| `ui.content` | Editable template, policies, sample site |
| `ui.frontend` | Plain-JS/Sass sources, webpack → `clientlib-site` |
| `all` | The single container package Cloud Manager ships |
| `dispatcher` | Apache + Dispatcher configuration |
| `it.tests` | HTTP tests against a running instance |

## Configuration

**ContentBridge - JSON Exporter** (`PageJsonExporterImpl`, `UrlServiceImpl`):

| Property | Default | Notes |
|---|---|---|
| `publishDomain` | `""` (author), `http://localhost:5403` (publish) | Prepended to DAM paths. Empty means relative paths. |
| `allowedRoots` | `/content` | **The security boundary of the API.** Wide by default so any site exports; narrow it per environment if you need to. |
| `maxDepth` | `3` | Levels below the app root walked for screens. |
| `cacheMaxAge` | `300` (author: `0`) | Drives the `Cache-Control` header. |

> **`-plugin org.apache.sling.bnd.models.ModelsScannerPlugin` in the parent POM is load-bearing.**
> It generates the `Sling-Model-Classes` manifest header. Remove it and the bundle still builds and
> starts cleanly, but no `@Model` class is ever registered — so every `adaptTo` returns null and the
> API answers with `"content": []` on every screen, with nothing in the logs. Verify registration at
> `/system/console/status-slingmodels`.

> **Do not add a `SlingServletResolver` configuration to this project.** Path-bound servlets need
> no allow-listing — `@SlingServletPaths` works out of the box on the SDK and on AEM as a Cloud
> Service. The `servletresolver.paths` property is *not* an allow-list; it is the set of paths from
> which any script or servlet may be executed, and it defaults to `/`. Narrowing it stops `/libs`
> scripts from running and takes the whole author UI down with a 500 on `/aem/start`.

## Security notes

- The endpoint is **read-only** (`SlingSafeMethodsServlet`) and enforces `allowedRoots` before it
  touches the resource resolver.
- It runs as the **request user**, so AEM ACLs still apply — it is not a service-user backdoor.
- Rich text is exported verbatim. Sanitising or safely rendering that markup is the client's job.
