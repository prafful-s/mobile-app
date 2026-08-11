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

## No pipeline bypass for Cloud Manager's image-build / code-quality gates

**Decision**: user twice asked whether the Build Images / code-quality gate could be bypassed.
Answer both times: no — these are hard Cloud Manager pipeline gates, not configurable/skippable
from repo config. Every failure was root-caused and fixed for real (see entries above) rather
than worked around.
