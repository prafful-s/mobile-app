# ContentBridge — Decision Log

Chronological. One entry per non-obvious decision: what, why, alternatives rejected. Append,
don't rewrite history — if a decision is later reversed, add a new entry linking back rather than
editing the old one.

Maintenance rule: **append an entry on every major decision** (schema/contract change, rejected
approach, root-cause fix for a recurring failure, config default change). Skip routine/obvious
edits.

---

## Servlet binding: resourceType, not fixed path

**Decision**: `PageJsonServlet` binds via `@SlingServletResourceTypes(resourceTypes =
"contentbridge/api")`, not `@SlingServletPaths`.
**Why**: Adobe AEMaaCS guidance — path-bound servlets skip Sling resource resolution, so ACLs on
the target resource are never consulted, dispatcher can't cache against a real resource, and the
request sits outside the standard resource/selector/extension contract. resourceType binding
means a real node is resolved first and read-permission is checked.
**Consequence**: the API URL is `/content/<site>/.../api/<name>.json`, driven by wherever a
`sling:resourceType=contentbridge/api` node exists — not a hardcoded servlet path.

## Content read via dedicated system user, with fallback

**Decision**: reads go through the `contentbridge-reader` system user (repoinit grants
`jcr:read` on `/content`), falling back to the caller's resolver + WARN log if that user can't
see the page.
**Why**: lets one endpoint serve every site on the instance regardless of the caller's own ACLs.
**Trade-off accepted**: response does NOT reflect the requesting user's permissions —
`allowedRoots` (OSGi config) is the real access boundary, so it must stay meaningful. Fallback
exists only to stop a misconfigured service user from taking author down (where the caller can
usually read anyway); on publish the caller is anonymous, so there is no real fallback there —
a broken service user is a hard outage on publish.

## Schema versioning via `?schema=` param, not a breaking change

**Decision**: v2 (hierarchical panels) added as an opt-in query param; v1 (flat) stays default.
**Why**: existing clients must keep working unmodified. Versioned contract > replacing the
contract.

## Fixed-skeleton mode + extras passthrough for v2 mapping rules

**Decision** (see `docs/superpowers` design note + commits `8fc0800`..`3078fa2`): v2-mode
component rules emit a fixed key skeleton (all keys always present; nullable fields emit `""`
not `null`) plus an `extras` bucket for any authored CRX property the rule doesn't explicitly
consume.
**Why**: downstream client code binds to a stable key set; `null`/missing keys break naive
consumers. Passthrough avoids silently dropping authored data the rule doesn't yet know about.
**Rejected alternative**: strict allowlist mapping that only ever roundtrips declared fields —
rejected because content authors regularly add ad-hoc properties that still need to survive to
the JSON output for debugging/forward-compat.

## `fromParent` operator for section-level styling

**Decision** (`0470686`): a rule can read a value from the component's parent container (e.g.
`title.textAlignment` reads the enclosing section's `sec-alignment`), not just the component's
own node.
**Why**: some visual properties are authored once at section level and meant to cascade to all
children — duplicating them per-component would drift.

## `-conditionalpackage` kept at full `org.apache.sling.servlethelpers.*` wildcard

**Decision** (`1e29a2a` then reverted in `6653314`): tried narrowing the bnd embed to
`org.apache.sling.servlethelpers.internalrequests.*` to shed `Mock*` classes flagged by Sonar
rule CQBP-84 (@ProviderType implemented by non-Adobe code). Broke bundle resolution:
`SlingInternalRequest`'s base class `InternalRequest` holds `MockSlingHttpServletRequest`/
`MockSlingHttpServletResponse` as real runtime fields, not test-only scaffolding.
**Final state**: reverted to the full wildcard. The resulting CQBP-84 finding is accepted as
inherent to Apache Sling's own library design — not fixable from this codebase without vendoring
or replacing the library.

## PATH_TRAVERSAL_IN test findings fixed by removing URL/URI, not by swapping API

**Decision**: `indexMatchesTheDirectory()` tests (`ComponentMappingTest`, `FormSchemaServiceTest`)
were flagged by findsecbugs. Swapping `getResource(...).toURI()` → `new File(uri)` /
`Paths.get(uri)` did **not** clear the finding — findsecbugs flags both identically.
**Real fix**: eliminate URL/URI from the call chain entirely — hardcoded relative literal path
(`Paths.get("src/main/resources/contentbridge/mappings")`), relying on Surefire's working
directory defaulting to the module base.

## Cloud Manager JDK bumped 11 → 17

**Decision** (`9615003`): `.cloudmanager/java-version` set to `17`.
**Why**: AEM SDK 2026.6.26908 ships Oak/Jackrabbit compiled for Java 17; forcing 11 caused
`UnsupportedClassVersionError` at build time. Not optional/configurable — matches what the SDK
version requires.

## Dispatcher farm structure: no outer `/farms{}` wrapper in per-farm files

**Decision**: `available_farms/contentbridge.farm` and `enabled_farms/contentbridge.farm` start
directly with `/contentbridge { ... }`, not `/farms { /contentbridge { ... } }`.
**Why**: discovered the hard way — two consecutive fix attempts (renaming farm key
`contentbridge`→`default`, believing AEMaaCS required that literal name) failed identically with
"entry not recognized" in the dispatcher validator, proving the farm *name* was never the
problem. Fetched Adobe's own reference implementation (`adobe/aem-guides-wknd`'s
`dispatcher.any` + `available_farms/wknd.farm`) and confirmed: Adobe's baseline `dispatcher.any`
already opens `/farms { $include "enabled_farms/*.farm" }` — wrapping again inside the included
file double-nests it. Farm name is arbitrary (wknd's own reference uses `/publishfarm`).
**Process lesson locked in**: after a commit that edits generated/config content, always verify
with `git show HEAD:<path>` before declaring the fix done — an earlier commit (`cc35f8d`) had
its `git mv` land but not the actual content edit, and the pipeline re-failed identically against
what should have been fixed content. This was only caught by diffing `git show HEAD:...` output.

## `enabled_farms`/`enabled_vhosts` are plain file copies, not symlinks

**Decision**: real Adobe AEMaaCS convention is `available_*` = library, `enabled_*` = symlinked
copy. This repo uses plain duplicated files instead.
**Why**: symlinks are unsafe across a Windows checkout (git-for-windows symlink support is
inconsistent/needs elevated perms). The dispatcher validator flags the duplication as a
"suggestion" only, not a fatal error — accepted trade-off.
**Consequence**: any edit to a farm/vhost must be applied to BOTH `available_*` and `enabled_*`
copies, and kept content-identical (verified by `diff` before each dispatcher commit).

## Dispatcher CORS/cache rules broadened to the real serving path

**Decision** (`ae4d704`): vhost `LocationMatch` and cache `/rules` originally only matched
`/apps/contentbridge/api.json` (author-only convenience path). Broadened to also match
`/content/*/api/*.json` — confirmed via `PageJsonServlet`'s actual resourceType binding
(no fixed path) that this is the path that actually serves on publish; `/apps/*` is denied to
anonymous on publish regardless of dispatcher config, so the old rule set was effectively
never exercised in production traffic.
**Also**: cache `/invalidate` broadened from `/content/contentbridge/*` (this repo's own demo
content) to `/content/*` — the API is designed to export any site under `/content`, not just the
shipped demo, so invalidation was too narrow for real multi-site use.
**Trigger**: found during an explicit re-audit requested by the user ("once again validate the
dispatcher configs"), not from a failed pipeline run — worth doing occasional non-failure-driven
audits, since the validator doesn't catch "technically valid but pointing at the wrong path".

## Title size: `map` operator added to the rule engine, h1-h6 → size token

**Decision**: `FieldRule` gained a generic `map` operator — a fixed value-lookup table applied
after `from`/`first`/`extract`/`fromParent` resolve, before `type` coercion. `title.json` uses it:
`h1`→`xlarge`, `h2`→`xlarge`, `h3`→`large`, `h4`→`medium`, `h5`→`small`, `h6`→`small`.
**Why**: Franklin/EDS-authored title blocks carry a raw heading tag (`titleType`: h1-h6); the
client contract wants a size token (xlarge/large/medium/small), not the tag. User only specified
h2-h6; h1 defaulted to the same top tier as h2 (no larger token exists in the vocabulary) —
flagged here since it's an assumption, not a given requirement.
**Not touched**: `TitleModel.java` (the curated Sling Model for the native AEM `title` component,
resourceType `contentbridge/components/title`) — its dialog already authors `size` directly as
xlarge/large/medium/small, no heading tag involved, so no mapping needed there. Two separate
title code paths exist in this codebase; this change only affects the Franklin-block mapping
rule (`core/src/main/resources/contentbridge/mappings/title.json`).
**Unmapped-value behavior**: an authored value with no entry in `map` passes through unchanged
(same "visible in the response, not silently dropped" philosophy as the rest of the engine).

## Link fields: `path` type strips scheme+host, keeps only path/query/fragment

**Decision**: `FieldRule` gained a `type: "path"` coercion — if the resolved value is an absolute
`http://`/`https://` URL, only `path?query#fragment` is kept; anything else (already-relative
path, anchor, `mailto:`, `tel:`, malformed URL) passes through unchanged. Wired onto every
link-carrying field: `button.linkTo`, `card.link`, `hero.linkTo` (extracted from the anchor
`href`), `image.linkTo`, `offer.linkTo`.
**Why**: EDS/Franklin authoring (and rich-text anchors) can produce a link against whichever host
was open at authoring time (author, preview, or a stale publish host) — baking that host into the
JSON response would send clients to the wrong environment. A client consuming the API already
knows its own host; it only needs the path.
**Consistent with**: the existing "don't guess, stay visible" rule - a malformed URL is returned
as authored rather than dropped or half-parsed.

## `link`/`linkTo` excluded from the global host-prefixing pass

**Decision**: `ComponentTreeWalker.prefixAssets` now skips the output keys `link` and `linkTo`
before running `ContentPaths.prefix`, so those fields keep whatever relative `/content/...` path
they resolved to instead of getting the delivery host prepended.
**Why**: `ComponentTreeWalker.componentsOf()` runs `prefixAssetsDeep` over the *entire* finished
component tree, keyed only on value shape (any string starting with `/content/` gets the host) -
by original design, deliberately, so image/DAM/CF references work without an AEM session to
resolve them against. That pass runs **after** mapping rules, so it was silently undoing the
`type: "path"` host-stripping added earlier the same day for `button.linkTo`/`card.link`/
`hero.linkTo`/`image.linkTo`/`offer.linkTo`, and doing the same to any Franklin block's raw
`link` property via the `GenericComponent` fallback (this is what a `product-card` Franklin block
with an authored `link` field was hitting - see screenshot triage). A `link`/`linkTo` is a
navigation target the client already knows how to resolve against its own host (in-app routing,
or its own environment); an image/DAM/CF reference is a resource that must be fetched from
exactly this delivery host - the two need opposite treatment even when both happen to be a bare
`/content/...` string.
**Reverses**: this was previously deliberate, documented, and tested behavior (see
`ContentPaths.java`'s class doc and the now-updated `ContentPathPrefixTest`, `FranklinExportTest`,
`SiteHostTest`, `ComponentMappingTest.extractedLinksAreExternalised`) - explicit user request to
flip it, not a bug fix. Markup links inside authored rich text (`<a href="/content/...">` in a
`text`/`htmlText` field) are untouched - out of scope, not requested.
**Scope note**: keyed on the literal target key name (`link`, `linkTo`), not the resourceType or
component - covers every mapping rule that emits one of those two names, plus the generic
Franklin-block fallback, with a two-entry `Set` rather than per-component special-casing.

## Screen-key collision now logs a warning instead of silently dropping a page

**Decision**: `PageJsonExporterImpl.buildScreens` logs a WARN when two pages resolve to the same
`screens` key (via `screenKey()` - authored `pageName` property, falling back to the JCR node
name) before overwriting.
**Why**: found via a real incident - binji-site had `/content/binji-site/.../form-test`
(jcr:title "Forms Test", a QA fixture) authored with `pageName: "movies"`, colliding with the
actual `/content/binji-site/.../movies` page which also has `pageName: "movies"`. Both mapped to
`screens.movies`; whichever the tree walk hit last silently overwrote the other with no trace in
the response - the real Movies page's content vanished from the API with zero signal anything
was wrong. The page that "went missing" per the user's report was never actually unfetched; it
was clobbered after being fetched.
**Not a code bug to "fix" further**: this is a content-authoring error (duplicate `pageName`) -
the real fix is on the content side (give the QA page a distinct `pageName` or delete it). The
warning exists so the *next* occurrence is diagnosable from logs instead of requiring a manual
CRXDE property-by-property comparison to find.

## Per-site product source defaults shipped as bundle data, not content

**Decision**: added `core/src/main/resources/contentbridge/products/sites.json` (site name →
`{graphQlEndpoint: "config/queryName", cfFolderPath}`) and a new package-private loader,
`SiteProductDefaults` (mirrors `ComponentMappings`'s classloader-`getResourceAsStream` + Jackson
load pattern, `EMPTY` fallback, per-entry error isolation). Wired as the **third and last**
precedence tier in `GraphQlQuery.resolve()` — request param > site's `placeholders` page > shipped
`sites.json` — and, separately, as the last-resort `productsPath` for the plain Content Fragment
read in `PageJsonExporterImpl.buildProducts()` when the GraphQL branch yields nothing.
**Why**: user asked to "maintain the configuration" under `core/src/main/resources` for known
sites (secur-financial, and an example "luma" site) rather than requiring every environment's
`placeholders` page to be hand-authored with `gqlConfig`/`gqlQuery`/`productsPath` before a site's
products work at all. Matches the existing convention in this codebase (mapping rules, form
schemas) of shipping config as bundle-resource JSON instead of Java.
**Format**: `graphQlEndpoint` is `"<config>/<queryName>"` as one string (matches the two path
segments of `/graphql/execute.json/<config>/<queryName>` — the same shape a pasted execute URL or
this file both use), split on the first `/` at load time rather than as two JSON keys.
**Verified against the running local AEM instance** (`:6502`, admin/admin):
`/conf.1.json` → 5 conf roots, only `dsn-eds-configuration` has
`settings/graphql/persistentQueries` (5 queries: `productsListByPath`,
`productDescriptionByPathAndSKU`, `productListByCategory`, `productListByPathBinji`,
`productDescriptionByPathAndSKUBinji`). `secur-financial` as a GraphQL config does **not** exist
on this local instance — `GET /graphql/execute.json/secur-financial/product-card-information` →
404 `PersistenceError`. It's presumed to exist on the deployed `author-p35437-e2207568` cloud
instance the user pasted URLs from (not verifiable from here). Not a blocker: `GraphQlQuery`
already falls through to Content Fragments on any empty/failed query, and the shipped
`cfFolderPath` (`/content/dam/secur-financial`) backstops that fallback independently.
**Precedence tests added** (`ProductExportTest`): `shippedDefaultFolderIsUsedAsLastResort` (no
config anywhere → shipped default fires) and `authoredPathOutranksShippedDefault` (a `productsPath`
property on the site root still wins) — same file, same fixture style as the rest of the product
export tests.

## `contentFragmentRef`: mapping rules can dereference a Content Fragment

**Decision**: added a new rule-file key, `contentFragmentRef`, naming which of a component's own
properties holds a path to a Content Fragment. `ComponentTreeWalker.withContentFragmentRef` reads
that property, resolves the fragment via `ContentFragmentReader.read()` (new class, extracted from
`ProductExporter`'s existing CF-read logic so both share one implementation), and merges the
fragment's elements into the source map used for field resolution - the component's own authored
properties win on a name clash (`merged = fragmentElements; merged.putAll(authored)`).
**Why**: `product-card` blocks carry almost nothing of their own (`buttonText`, `link`, `ctastyle`,
a `contentFragmentFolder` reference) - the title, image and description a client needs live on the
referenced fragment, not the block. The existing rule engine only ever read the component's own
JCR node; there was no way to express "this field comes from what the component points at."
**Extras stay clean**: `appendUnconsumedExtras` still runs against the *original* `authored` map,
never the CF-merged one - a fragment field no rule targeted (e.g. `productSku`, `redirectingUrl`)
quietly doesn't appear, rather than leaking into extras. `contentFragmentRef`'s own property
(`contentFragmentFolder`) is added to `consumedProperties()` so it doesn't leak into extras either.
**Failure mode**: an unresolvable reference (blank, wrong path, not a fragment) logs a WARN and
falls back to `authored` unchanged - the component still exports with whatever it has, never fails
the whole page.

## `product-card` → `banner`, via a new `productCard.json` rule

**Decision**: added `core/src/main/resources/contentbridge/mappings/productCard.json` -
`match: ["product-card"]`, `component: "banner"` (same output name as `hero.json`, deliberately a
separate rule file since the field-sourcing is entirely different: hero/banner extracts from
authored rich text, product-card sources image/title from its referenced CF via
`contentFragmentRef: "contentFragmentFolder"`).
**Two open assumptions, flagged rather than silently guessed** (per user instruction to start
implementing before both were answered):
- `buttonVariant` value table: authored `ctastyle` → reference `buttonVariant`, via the `map`
  operator: `button`→`contained`, `cta-button`→`contained`, `cta-link`→`text`, unmapped values
  default to `contained`. **Not confirmed with the user/design system** - only a defensible guess
  from the three `ctastyle` values seen in real content.
- `linkTo`: reference's own example uses a route+query form
  (`"loan-application?productId=MC$1JXSft"`), not a content path. Implemented as the block's own
  `link` property instead (already a working, relative content path per the earlier `path`-type
  fix) - reproducing the route+query form would mean inventing a routing convention with no
  confirmed source (which route name, is `productId` the CF's `productSku`?). **Follow-up needed**
  if the reference's exact format is required.
**Verified against the real CF** (`/content/dam/secur-financial/en/fragments/credit-cards/
securfinancial-gold`, model `secur-financial-product`): fields are `productName`, `productImage`
(plain `/content/dam/...` path - Option A, the raw JCR read, not the richer GraphQL-resolved shape
with `_publishUrl`/`_dynamicUrl`; user explicitly chose the plain-path option), `productSku`,
`productCategory`, `productDescription` (richtext HTML), `redirectingUrl` (unset on this variant).
Only `productName`→`title` and `productImage`→`image` are consumed by the rule today; `subtitle`
is left unsourced (empty by skeleton default, matching the reference example's own `subtitle: ""`)
rather than guessed from `productCategory`.
**Tests**: `ProductCardMappingTest` - CF fields land in the banner shape, block's own fields still
apply, `ctastyle`→`buttonVariant` for both known values, an unresolvable fragment doesn't break the
export (falls back to block-only fields, empty title), and `contentFragmentFolder` never leaks into
extras.

## Screen/menu key is the JCR node name, `pageName` property dropped

**Decision**: `screenKey()` returns `kebab(page.getName())` only - the `jcr:content/pageName`
property is no longer read anywhere. Both the `screens` map keys and the `meta.navigation.menu`
array come from the same node-name source.
**Why**: on a live secur-financial pull, `menu` returned `["home","credit-cards","credit-cards","credit-cards","loans","dashboard","settings"]` - three distinct nav-linked
pages all carrying `pageName=credit-cards`. Same failure class as the earlier `form-test` /
`movies` screens-map collision, just surfacing in the menu instead. Diagnosis was always "an
author typed the same override into two different pages" - not a real feature request. Dropping
the property removes the collision surface entirely: JCR forbids sibling nodes with the same
name, so two same-key screens now means the exact same path is listed twice, which is a bug in
the nav authoring rather than a silent overwrite.
**Rejected**: (a) menu-only scope - would let a page's menu entry no longer match its screens
key whenever a legit `pageName` override existed, i.e. break every historically-honoured
override rather than just the collision cases; (b) validate uniqueness at read time - defers the
problem to a runtime WARN when we can just stop reading the property.
**Trade-off accepted**: any author who was renaming JCR nodes freely while pinning the client
key via `pageName` no longer can. On this codebase, the only `pageName` value in tests was
`home` on the `en` root - not a real override, just documenting the fallback. Same-name pages
under different parents (unrelated to `pageName`) still collide and still emit the existing
WARN in `buildScreens`.
**Tests**: updated `PageJsonExporterImplTest.menuMatchesScreens`,
`FranklinExportTest.childPagesBecomeScreens` (and its `content("home")` helpers all switched to
`content("en")`), `HierarchicalSchemaTest.content()` helper. 203/203 core tests green.

## Form-field visibility gated by authored `show*` properties (Option A: filter, drop)

**Decision**: form schema entries can carry `"visibleIf": { "prop": "showcreditcard", "default":
false }`. `FormFieldFactory.isVisible()` reads the property off the authored block; when falsy
(or absent and `default` is false), the field is dropped from the emitted `content[]`. Remaining
fields renumber to a contiguous 1..N order.
**Why**: reference client (`parent-repo\blocks\create-account\create-account.js`,
`blocks\join-us\join-us.js`) toggles field visibility via authored booleans
(`showcreditcard`, `showaddress`, `showdateofbirth`, `showloyaltyprogram`, `showprivacypolicy`,
`showcommunicationpreferences` for create-account; `showdeliverysection`, `showconsentcheckbox`,
`show-zip-code` for join-us). Our exporter previously emitted every schema field regardless -
verified on `form-test/create_account` where `showcreditcard=false` still produced credit-card
fields in the payload.
**Rejected — Option B (annotate `hidden:true` on the field)**: mirrors reference's CSS-hide
semantics exactly but leaves the payload full-size and forces every client to honour a `hidden`
flag. Since our render targets don't have a stateful "re-show later" case (these are page-time
author config, not runtime state), the drop-out is safe and produces a smaller payload.
**Trade-off accepted**: a client that expected a stable field set now sees a variable one -
mitigated by the contiguous 1..N ordering (no gaps that would look like dropped fields), and by
the fact that field visibility was authored in the first place, so a stable "field always
present" contract was never really on offer.
**Scope**: 2 schemas touched (create-account.json, join-us.json). Rest of the 18 shipped
schemas have no toggle-style authored properties in the reference JS, so nothing to annotate.
Engine addition is one static method on FormFieldFactory + one filter in FormBlockBuilder.
**Tests**: 5 new tests in `FormBlockV2Test`
(`createAccountFullSchemaWithoutFlags`, `createAccountAddressAndDobHidden`,
`createAccountCreditCardShown`, `visibleIfAcceptsStringBooleans`, `orderIsCompactedAfterFiltering`)
covering default resolution, drop-out on false, string-'true'/false coercion (CRXDE stores
booleans as strings), and ordering compaction. Updated `dateLookingTextStaysText` to set
`showcreditcard=true` since it needs `cardExpiry`.

## `visibleIf` extended to variant-based gates (equals / notEquals / equalsAny)

**Decision**: `visibleIf` in form schema entries now supports three additional operators alongside
the original truthy `default` check: `equals`, `notEquals`, `equalsAny`. All compare with a
trimmed lower-case normalisation matching reference JS's `normalizeVariant`.
**Why**: audit-pass of all 18 form blocks against `parent-repo\worksapce\migration-parent-repo\blocks\`
turned up a second class of conditional rendering the exporter was missing - variant-based hard
emit/omit. `create-account` alone hides 8 fields on non-matching variants (`wkndFlyMember`
requires `wknd-fly`; `companyName`/`companySize`/`industry`/`topicOfInterest` require `bodea`;
`shoeSize`/`shirtSize`/`favoriteColor` require `luma`; `frescopaOwner` requires `frescopa`;
`favouriteNewsCategory`/`accountType` require `expnews`). `test-drive` hides
address/city/zipCode when `variant=schedule-appointment`. `checkout-shipping` gates
`cardNumber`/`lumaLoyalty` on two boolean flags (`showcardnumberfield`/`showloyalty`) - these
were previously missed because the audit only looked at `create-account` and `join-us`.
**Rejected — separate operator syntax**: extending the existing `visibleIf` object with
`equals`/`notEquals`/`equalsAny` keys stays inside the one gate the schema already understood,
so a rule reads top-to-bottom without switching between two dispatch shapes.
**Trade-off accepted**: authors who set an unrecognised variant value get every variant-gated
field hidden. Consistent with reference JS's behaviour (an unknown variant matches none of the
`is*Variant` branches).
**Scope**: 3 more schemas annotated (create-account.json for 8 variant fields,
checkout-shipping.json for 2 boolean gates, test-drive.json for 3 notEquals gates). Engine change
is one static method extended in FormFieldFactory - no signature changes.
**Tests**: 6 additional in `FormBlockV2Test`
(`createAccountVariantGating`, `createAccountBodeaVariant`,
`createAccountNoVariantHidesVariantFields`, `testDriveScheduleAppointmentHidesAddress`,
`testDriveDefaultVariantKeepsAddress`, `checkoutShippingBooleanGates`). 214/214 core tests green.

## No pipeline bypass for Cloud Manager's image-build / code-quality gates

**Decision**: user twice asked whether the Build Images / code-quality gate could be bypassed.
Answer both times: no — these are hard Cloud Manager pipeline gates, not configurable/skippable
from repo config. Every failure was root-caused and fixed for real (see entries above) rather
than worked around.
