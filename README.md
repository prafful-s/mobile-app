# ContentBridge

An AEM as a Cloud Service project whose only job is to publish authored pages as a stable JSON
contract. Authors work in the normal AEM page editor; clients read a single GET endpoint.

```
GET /apps/contentbridge/api.json?path=/content/contentbridge/us/en
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

The build targets `localhost:6502` by default. For a different port:

```bash
mvn clean install -PautoInstallSinglePackage -Daem.port=4502
```

Then:

```bash
curl -u admin:admin \
  'http://localhost:6502/apps/contentbridge/api.json?path=/content/contentbridge/us/en&pretty=true'
```

If every screen comes back with `"content": []`, check
`/system/console/status-slingmodels` — see the note under *Configuration*.

---

## The API

| | |
|---|---|
| Endpoint | `/apps/contentbridge/api.json` |
| Method | `GET` |
| Binding | resource type `contentbridge/api`, extension `json` |
| `path` | **required.** Repository path of a page. Must sit under an allowed root. |
| `pretty` | optional. `true` indents the response. |
| Response | `application/json;charset=UTF-8` |

The servlet is bound by **resource type**, not by path. Adobe advises against `@SlingServletPaths`
on AEM as a Cloud Service: a path-bound servlet bypasses resource resolution entirely, so the ACLs on
the requested resource are never consulted and the dispatcher has no real resource to cache against.
Binding to `contentbridge/api` means the request resolves the node at `/apps/contentbridge/api`
first, which the requesting user must be permitted to read.

> **On publish, `/apps` is not readable by anonymous.** For a public endpoint, create a node under
> `/content` carrying `sling:resourceType="contentbridge/api"` and call that instead — the same
> servlet answers. The `/apps` node is the author-side default.

### Service user

Content is read through the **`contentbridge-reader`** system user, not the requesting user. That is
what allows a single endpoint to export any site under `/content`, including projects with no
knowledge of ContentBridge.

Created by repoinit in `ui.config`:

```
create service user contentbridge-reader with forced path system/cq:services/contentbridge

set principal ACL for contentbridge-reader
    allow jcr:read on /content
end
```

> **The `system/cq:services` path is not cosmetic.** Oak enables principal-based authorization only
> for principals under a supported subtree, which AEM configures as `system/cq:services`. Create the
> user anywhere else and `set principal ACL` cannot store the entry — the user is created, the
> resolver opens happily, and every read silently returns nothing. The symptom is a 404 from the API
> on a page that plainly exists. `forced path` (rather than `path`) relocates a user that was already
> created at the wrong place.

and bound to the bundle by a `ServiceUserMapperImpl.amended` factory config:

```
contentbridge.core:contentbridge-reader=contentbridge-reader
```

Read-only by design — `jcr:read` and nothing else, so the endpoint cannot write even if a future
change tried to.

> **This removes per-user access control from the endpoint.** Anyone who can reach the URL sees
> whatever `allowedRoots` permits, regardless of their own JCR permissions. `allowedRoots` is now the
> only boundary, so treat it as the security control it is — and on publish, restrict the endpoint at
> the dispatcher rather than relying on repository ACLs.

The left-hand side of the mapping is the **bundle symbolic name**. It is `contentbridge.core` by
default (bnd derives it from the artifactId); confirm at `/system/console/bundles` if the resolver
fails to open, since a mismatch there is the usual cause of a `LoginException` at runtime.

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
  'http://localhost:6502/apps/contentbridge/api.json?path=/content/contentbridge/us/en&pretty=true'
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
  Both derive from the authored navigation page — see *Navigation* below.
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

### Products

`products` is built from the site's Content Fragments, projected onto one fixed key set:

```
id  sku  name  image  price  type_id  category  thumbnail
description  category_ids  importedFrom  category_uids  short_description
```

**Finding the catalogue**, most explicit first — each step falls through only when it yields nothing:

1. the **`productsPath` request parameter** — points at any catalogue without touching content
2. a **`productsPath` entry on the site's placeholders page** — the authored per-site answer, sitting
   alongside `hostname`
3. a **`productsPath` page property** on the site root
4. a **search of `/content/dam/<site>`** for a folder named `products` (configurable)

A configured path that does not exist is logged as a warning rather than silently replaced by a
search result, so a typo surfaces instead of appearing to work.

Fragments nested in category sub-folders (`products/beverages/tea`) are collected — the walk
descends through folders.

**Mapping the fields.** Sites model products differently — `sku` here, `productSku` there — so each
output key lists candidate element names and takes the first that matches. Extend the table in OSGi
rather than in code:

```
sku=productCode,itemNumber
price=listPrice
```

`id` and `sku` fall back to the fragment name, and `name` to its title, so a product always has an
identity.

**Types are coerced, not passed through.** `price` becomes a number, `category_ids` a list of
integers, `category_uids` a list of strings. Every key is always present: missing strings are `""`
and missing lists `[]`, so clients can index without null checks. `price` is the deliberate
exception — absent means `null`, because `0` would read as free.

DAM references in `image` and `thumbnail` get the site's publish host, same as component assets.
Reading is capped (`maxProducts`, default 200) so a large catalogue cannot stall the endpoint; the
response is truncated and a warning logged. If no fragments are found, a hand-authored
`productsJson` on the page is used instead.

### Asset URLs

DAM references are prefixed with the **site's own** delivery host, read from a `hostname` entry on
`/content/<site>/placeholders`. A single globally-configured domain would be wrong as soon as a
second site is exported, so the value is resolved per site, once per response.

The authored value is the *author* host; clients need publish, so the leading host segment is
swapped:

```
placeholders   hostname = https://author-p153659-e1620914.adobeaemcloud.com
emitted        image    = https://publish-p153659-e1620914.adobeaemcloud.com/content/dam/...
```

Both shapes are supported — a property literally named `hostname`, and the key/value row that Edge
Delivery placeholder sheets use. A value already pointing at publish is left alone. With no
`placeholders` page, DAM paths stay relative.

Only values starting `/content/dam/` are prefixed, so page links and colour tokens are untouched.
The `author` → `publish` segments are configurable (`authorHostSegment`, `publishHostSegment`).

### Navigation

`meta.navigation.menu` and `screens` are both built from the **authored navigation page**, not by
listing child pages. Listing children produces an accidental menu: every page in JCR order, including
`nav`, `footer` and `search` fragments that are not destinations at all.

The nav page is located in this order, and the first hit wins:

1. a `navPath` property on the site root — explicit override for sites that do not follow convention
2. a child page named `nav`, `header` or `navigation` (configurable via `navPageNames`)
3. no nav page → fall back to listing child pages, so sites without one keep working

Menu entries come from **every `<a href>` found in any HTML-bearing property on that page**, in
authoring order. Nothing is keyed on a particular component or node name, which is what makes it work
on any site whose navigation is an authored link list:

```html
<ul>
  <li><a href="/content/acme/us/en/campaigns.html">Campaigns</a></li>
  <li><a href="/content/acme/us/en/news.html">News</a></li>
</ul>
```

Each href is resolved to a page (`.html` stripped, site-relative paths supported) and then to its
screen key — which is what keeps `menu` and `screens` referring to the same things. Links that do not
resolve to a page (external URLs, `mailto:`, anchors) are skipped and logged at DEBUG.

If an href does not resolve, its final segment is retried under the current site root. Content
packages are routinely built at one path and installed at another, which leaves every nav link
pointing at a tree that does not exist on this instance; without the retry the whole menu would
silently fall back to child listing. When *no* link resolves, a WARN names the first few so the cause
is visible in the log rather than inferred from a suspiciously long menu.

Two consequences worth knowing:

- **A page not linked from the nav is not a screen.** Deep-linking still works: request that page
  directly and you get an envelope containing it.
- **The home page is prepended** when the nav does not link it, since navigations usually link home
  from the logo. Set `includeHomeScreen=false` to switch that off.

Parsing uses **jsoup, embedded in the bundle** rather than imported. AEM ships jsoup internally, but
it is not documented API, so relying on that export would be fragile across SDK versions.

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

The `aem.port` property defaults to **6502**, so the plain commands target that instance.

```bash
# Full build + deploy to the local author instance on :6502
mvn clean install -PautoInstallSinglePackage

# Java only, hot-swapped into the running OSGi container
mvn clean install -PautoInstallBundle -pl core

# Content only
mvn clean install -PautoInstallPackage -pl ui.content

# Publish instance (defaults to :6503)
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

1. Author: <http://localhost:6502/sites.html/content/contentbridge/us/en>
2. API: <http://localhost:6502/apps/contentbridge/api.json?path=/content/contentbridge/us/en&pretty=true>

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
| `publishDomain` | `""` (author), `http://localhost:6503` (publish) | Prepended to DAM paths. Empty means relative paths. |
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
