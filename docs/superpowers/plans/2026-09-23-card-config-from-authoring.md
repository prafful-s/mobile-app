# Card Config From Page Authoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read each card's mobile appearance from the authored EDS card block (`mobilecardstyle`, `textcolor`, `mobileimagestyle`) instead of from per-site `defaults.json` entries, and retire the site-level card config once authoring covers it.

**Architecture:** The authoring surface already exists — the parent repo branch `cards-specific-changes` adds a **Mobile Specific** tab to the `card` model (`mobilecardstyle`, `mobileimagestyle`, `custommobilestyle`), alongside the pre-existing `textcolor`. Those become JCR properties on the authored node, which `ComponentTreeWalker` already passes to the mapping rules as authored props. So the mobile side is a change to one mapping rule file, `mappings/card.json`, plus per-site config deletions — **no Java change**. Sequencing matters more than code: the mapping change is made backwards-compatible first (authored value wins, existing `style`-derived behaviour is the fallback), and each site's config entry is deleted only after that site's cards are actually authored.

**Tech Stack:** AEM as a Cloud Service (Java 21, OSGi), the mobile exporter's declarative mapping DSL (`core/src/main/resources/edsMobileApp/mappings/*.json`, evaluated by `ComponentMapping`/`FieldRule`), EDS/Franklin block models (`blocks/cards/_cards.json` in `migration-parent-repo`).

**Spec:** No separate spec doc. The requirement as given: *"all the card configuration which we added to site defaults.json has to be gathered from the pages card mobile authoring; changes regarding is done on parent migration-parent-repo/tree/cards-specific-changes"*. The findings this plan argues from are recorded in the **Evidence** section below, gathered 2026-09-23.

## Global Constraints

- **No Java changes.** Everything in Tasks 1–4 is `mappings/card.json` and `sites/*/defaults.json`. If a task appears to need Java, stop and raise it.
- **No behaviour change without authoring in place.** Every mapping change must leave un-authored content exporting byte-identically. Authored value wins; absent authored value falls back to today's `style`-derived result.
- **Branch:** cut from `main`, per project convention (`<topic>-site-mobile-map` style). Merge into `develop` to test, then to `main`.
- **`mobilecardstyle` vocabulary** (from `_cards.json`, verbatim): `one-line`, `overlay`, `below`, `partial-overlay`. Model default is `below`.
- **`cardType` vocabulary the client consumes** (from `mappings/card.json` + `productCard.json`): `stacked`, `image-text-one-line`, `overlay`, `partial-overlay`.
- **`textcolor` field description** (verbatim): *"Optional hex color for card text (e.g. #fff, #000000, or 333). Leave empty for default."*
- **Verification is export-diff, not unit tests.** `core` has no test sources (`core/src/test` does not exist). Each task verifies by rebuilding, deploying to the local AEM at `http://localhost:6502`, exporting the affected site, and diffing the specific component ids. For sites with no content on that instance, verification is an offline replay against a saved export.
- **AEM at 6502 must be running** for any task's verification. At the time of writing it returns `000` (down). Do not mark a task complete on a build alone.

---

## Evidence this plan is built on

Gathered 2026-09-23. An executor should not re-derive these, but should not trust them blindly either — re-check any that a step depends on.

1. **`cards-specific-changes` is exactly the enabling change.** `git diff origin/main..HEAD` on that branch touches only `blocks/cards/_cards.json` and the generated `component-models.json`, adding the `mobileSpecific` tab with `mobilecardstyle`, `mobileimagestyle` and `custommobilestyle`. It is **2 commits ahead of that repo's `origin/main` and not merged** — so the fields are not live for authors yet.
2. **The exporter ignores nearly all of the card model.** `mappings/card.json` reads only `image`/`fileReference`/`imageRef`, `text`/`richText`/`jcr:text`, `style`, `link`, `mobileimagestyle`. Authored but ignored: `textcolor`, `mobilecardstyle`, `overlaybackground`, `ctastyle`, `alignment`, `backgroundcolor`, `selectable`, `customstyles`, `custommobilestyle`, `buttoneventtype`, `mobilebuttoneventtype`, `imagestyle`.
3. **`cardType` is currently derived from the desktop `style` field**, not from anything mobile-specific.
4. **`styles` rules use the same `FieldRule` shape as `fields`** (`ComponentMapping.fieldRules(definition.get("styles"))`), so `first`, `map`, `default`, `nullable` are all available in a styles rule.
5. **`map` passes a value through unchanged** when there is no exact key and no `"*"` entry (`FieldRule.translate`). This is what lets an arbitrary hex colour survive a rule that also maps enumerated tokens.
6. **The two vocabularies do not collide.** `mobilecardstyle` ∈ {one-line, overlay, below, partial-overlay}; `style` ∈ {"", image-top, image-bottom, image-left, image-right, teaser-overlay, teaser-card, icon-cards}. No shared token, so one `map` can serve both sources.
7. **The `cards` container has no model** — `_cards.json` defines only the `card` model; the `cards` definition carries `filter: cards` and no `model`. **There are no authorable fields on the container.**
8. **Current card-related site config** (on `develop`, 2026-09-23):

| site | keys |
|---|---|
| carvelo | `renderLinklessCards: true`, `linklessCardType: "image-text-one-line"`, `screenList` |
| citi-signal | `renderLinklessCards: true` |
| halliby | `renderLinklessCards: true`, `linklessCardType: "image-text-one-line"` |
| secur-financial | `componentStyles: { "en.banner.65721cdd": {...}, "en.card.b7ac2fab": { "color": "#FFFFFF" } }` |
| wknd-fly | `screenList`, `componentMeta: { "wknd-club.panel.b1059ca7": { layout: horizontal, ... } }` |

## Scope: what can and cannot move to authoring

**Can move (per-card properties):**

| site config | replaced by authored field |
|---|---|
| `linklessCardType` (carvelo, halliby) | `mobilecardstyle` per card |
| `componentStyles` → `en.card.b7ac2fab.color` (secur-financial) | `textcolor` per card |

**Cannot move — no authoring source exists:**

| site config | why |
|---|---|
| `renderLinklessCards` | not appearance; decides whether a `link: "#"` card is exported at all. No field expresses it. |
| `screenList` (carvelo, wknd-fly) | colours for the cards-block→`screenList` conversion, a container-level concern. |
| `componentMeta` → `wknd-club.panel.b1059ca7` (row layout) | row layout lives on the enclosing **panel**; per Evidence 7 the container has no authored fields. |
| `componentStyles` → `en.banner.65721cdd` (secur-financial) | a hero, not a card. Out of this requirement's scope — see Deferred. |

**This is the plan's main caveat: the requirement cannot be fully satisfied for container-level settings without adding fields to the `cards` container model in `migration-parent-repo`.** That is a change in the other repo and is out of scope here. Raise it with whoever owns that repo.

## File Structure

| file | responsibility after this plan |
|---|---|
| `core/src/main/resources/edsMobileApp/mappings/card.json` | single source of card projection; reads `mobilecardstyle` then `style` for `cardType`, `textcolor` then `style` for `styles.color` |
| `core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json` | loses its `en.card.*` `componentStyles` entry; keeps the banner entry |
| `core/src/main/resources/edsMobileApp/sites/halliby/defaults.json` | loses `linklessCardType`; keeps `renderLinklessCards` |
| `core/src/main/resources/edsMobileApp/sites/carvelo/defaults.json` | loses `linklessCardType`; keeps `renderLinklessCards` and `screenList` |

No new files. No Java files touched.

---

### Task 1: `cardType` reads `mobilecardstyle`, falling back to `style`

Additive and behaviour-preserving: content with no `mobilecardstyle` keeps exporting exactly as today.

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/mappings/card.json` (the `cardType` field rule)
- Test: none — see Global Constraints. Verification is export-diff.

**Interfaces:**
- Consumes: authored props `mobilecardstyle` (new, from `cards-specific-changes`) and `style` (existing).
- Produces: `content.cardType` ∈ {`stacked`, `image-text-one-line`, `overlay`, `partial-overlay`} — unchanged value set, so no client change.

- [ ] **Step 1: Confirm the enabling authoring change is live**

The fields must exist in AEM before this is meaningful. In `migration-parent-repo`:

```bash
git log --oneline origin/main..cards-specific-changes -- blocks/cards/_cards.json
```

Expected: the two commits adding `mobileSpecific`. If they are **not** yet on that repo's `main` and deployed, this task still merges safely (fallback path), but it cannot be verified with real authored values — record that and continue.

- [ ] **Step 2: Capture the before-state for a site with local content**

```bash
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/halliby" -o /tmp/halliby-before.json
python -c "import json;d=json.load(open('/tmp/halliby-before.json',encoding='utf-8'));\
t=[];\
walk=lambda i:[ (t.append(((c.get('meta') or {}).get('id'),(c.get('content') or {}).get('cardType'))) if (c.get('meta') or {}).get('component')=='card' else None, walk(c['content']) if isinstance(c.get('content'),list) else None) for c in i];\
[walk(s.get('content') or []) for s in d['screens'].values()];print(len(t));print(sorted(set(x[1] for x in t)))"
```

Expected: a count of cards and the set of `cardType` values in use. Save the output — Step 6 compares against it.

- [ ] **Step 3: Replace the `cardType` rule**

In `mappings/card.json`, replace the existing `cardType` rule with this. Both vocabularies share one `map` because they do not collide (Evidence 6). `first` picks `mobilecardstyle` when non-blank and falls through to `style` otherwise.

```json
    { "target": "cardType", "first": ["mobilecardstyle", "style"],
      "map": {
        "one-line": "image-text-one-line",
        "overlay": "overlay",
        "below": "stacked",
        "partial-overlay": "partial-overlay",
        "": "stacked",
        "image-top": "stacked",
        "image-bottom": "stacked",
        "image-left": "image-text-one-line",
        "image-right": "image-text-one-line",
        "teaser-overlay": "partial-overlay",
        "teaser-card": "overlay",
        "icon-cards": "stacked",
        "*": "stacked"
      }, "default": "stacked" },
```

- [ ] **Step 4: Validate the JSON parses**

```bash
python -c "import json;d=json.load(open('core/src/main/resources/edsMobileApp/mappings/card.json',encoding='utf-8'));r=[f for f in d['fields'] if f['target']=='cardType'][0];print(r['first']);print(len(r['map']),'map entries')"
```

Expected: `['mobilecardstyle', 'style']` and `13 map entries`.

- [ ] **Step 5: Build and deploy**

```bash
mvn clean install -pl core -PautoInstallBundle -Daem.port=6502
```

Expected: `BUILD SUCCESS`. If AEM is down the install step fails — start AEM before this step.

- [ ] **Step 6: Verify un-authored content is unchanged**

Re-run Step 2's command into `/tmp/halliby-after.json` and diff:

```bash
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/halliby" -o /tmp/halliby-after.json
python -c "import json;a=json.load(open('/tmp/halliby-before.json',encoding='utf-8'));b=json.load(open('/tmp/halliby-after.json',encoding='utf-8'));print('identical:', json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True))"
```

Expected: `identical: True`. **If False, stop.** Halliby's cards carry no `mobilecardstyle` yet, so the fallback must produce the old value exactly. A difference means the merged map is wrong — compare the changed ids against Step 2's output before touching anything else.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/edsMobileApp/mappings/card.json
git commit -m "Read a card's mobile card type from its own authoring

cardType came from the desktop 'style' field, so the Mobile Specific tab
the cards block now ships - mobilecardstyle, with the client's own
one-line/overlay/below/partial-overlay vocabulary - had no effect on the
export at all.

The rule now reads mobilecardstyle first and falls back to style. The two
vocabularies share no token, so one map serves both sources and content
with nothing authored exports exactly as before.

Verified against a local export of halliby, whose cards carry no
mobilecardstyle: the payload is byte-identical before and after."
```

---

### Task 2: card text colour reads `textcolor`, falling back to the `style`-derived default

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/mappings/card.json` (the `styles` block)

**Interfaces:**
- Consumes: authored props `textcolor` (existing field, never read until now) and `style`.
- Produces: `styles.color` — either the authored hex, or `#ffffff` for the two teaser variants, or omitted.

- [ ] **Step 1: Capture the before-state**

```bash
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/carvelo" -o /tmp/carvelo-before.json
python -c "import json;d=json.load(open('/tmp/carvelo-before.json',encoding='utf-8'));\
t=[];\
walk=lambda i:[ (t.append(((c.get('meta') or {}).get('id'),(c.get('styles') or {}).get('color'),(c.get('content') or {}).get('cardType'))) if (c.get('meta') or {}).get('component')=='card' else None, walk(c['content']) if isinstance(c.get('content'),list) else None) for c in i];\
[walk(s.get('content') or []) for s in d['screens'].values()];\
print('cards',len(t));print('with color',[x for x in t if x[1]])"
```

Carvelo has 4 `overlay` cards (measured 2026-09-23), so expect colour on the teaser-derived ones.

- [ ] **Step 2: Replace the `styles` block**

Note there is deliberately **no `"*"` entry**: per Evidence 5, a value with no exact key and no wildcard passes through unchanged, which is how an authored hex survives.

```json
  "styles": [
    { "target": "color", "first": ["textcolor", "style"],
      "map": {
        "teaser-overlay": "#ffffff",
        "teaser-card": "#ffffff",
        "": "",
        "image-top": "",
        "image-bottom": "",
        "image-left": "",
        "image-right": "",
        "icon-cards": ""
      }, "nullable": true }
  ]
```

- [ ] **Step 3: Validate the JSON and the absence of a wildcard**

```bash
python -c "import json;d=json.load(open('core/src/main/resources/edsMobileApp/mappings/card.json',encoding='utf-8'));r=d['styles'][0];print(r['first']);print('has wildcard:', '*' in r['map'])"
```

Expected: `['textcolor', 'style']` and `has wildcard: False`. A `True` here would collapse every authored hex to one value — the whole point of the rule is lost.

- [ ] **Step 4: Build and deploy**

```bash
mvn clean install -pl core -PautoInstallBundle -Daem.port=6502
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Verify un-authored content is unchanged**

```bash
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/carvelo" -o /tmp/carvelo-after.json
python -c "import json;a=json.load(open('/tmp/carvelo-before.json',encoding='utf-8'));b=json.load(open('/tmp/carvelo-after.json',encoding='utf-8'));print('identical:', json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True))"
```

Expected: `identical: True`. **If False, stop** — carvelo authors no `textcolor`, so the `style` fallback must reproduce today's colours.

- [ ] **Step 6: Prove an authored value wins, on a real card**

Pick any card on a site with local content, set `textcolor` on its node in AEM, re-export, and confirm `styles.color` is that value:

```bash
curl -s -u admin:admin -F"textcolor=#123456" "http://localhost:6502<card-node-path>"
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/carvelo" \
  | python -c "import sys,json;d=json.load(sys.stdin);print('#123456' in json.dumps(d))"
```

Expected: `True`. Then remove the property again:

```bash
curl -s -u admin:admin -F"textcolor@Delete=" "http://localhost:6502<card-node-path>"
```

This is the only step that proves the new source is actually read rather than silently ignored. Do not skip it.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/edsMobileApp/mappings/card.json
git commit -m "Read a card's text colour from its own authoring

The card model has carried a 'textcolor' field - 'Optional hex color for
card text' - that the export never read, so a card whose text was
unreadable over its image could only be corrected by per-site config
pinned to that card's id.

styles.color now reads textcolor first and falls back to the style-derived
default. The map deliberately has no '*' entry: an unmapped value passes
through unchanged, which is what lets an arbitrary hex survive a rule that
also translates the teaser tokens.

Verified against a local export of carvelo, which authors no textcolor:
byte-identical before and after. Separately confirmed an authored
textcolor reaches styles.color."
```

---

### Task 3: retire secur-financial's card `componentStyles` entry

**Depends on Task 2** and on someone authoring `textcolor: #FFFFFF` on the card behind id `en.card.b7ac2fab`.

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json`

- [ ] **Step 1: Author the value, then confirm it reaches the export**

secur-financial has no content on the 6502 instance, so this must be verified on an instance that does have it. Export the site and check:

```bash
python -c "import sys,json;d=json.load(sys.stdin);\
f=lambda i:[x for c in i for x in ((([c] if (c.get('meta') or {}).get('id')=='b7ac2fab' else [])) + (f(c['content']) if isinstance(c.get('content'),list) else []))];\
hits=[x for s in d['screens'].values() for x in f(s.get('content') or [])];\
print(json.dumps(hits[0].get('styles')) if hits else 'card b7ac2fab NOT FOUND')"
```

Expected: `{"color": "#FFFFFF"}` — and it must still be that **after** Step 2 removes the config, which is the actual test.

- [ ] **Step 2: Delete only the card entry, keeping the banner entry**

`componentStyles` must survive with its banner key intact — that one has no authoring replacement (see Scope).

```json
  "componentStyles": {
    "en.banner.65721cdd": { "color": "#333333" }
  }
```

- [ ] **Step 3: Validate**

```bash
python -c "import json;d=json.load(open('core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json',encoding='utf-8'));k=list(d['componentStyles']);print(k);assert k==['en.banner.65721cdd'],k;print('banner entry kept, card entry gone')"
```

- [ ] **Step 4: Build, deploy, re-export and confirm the colour is unchanged**

Re-run Step 1's check. Expected: still `{"color": "#FFFFFF"}`, now sourced from authoring. **If the colour disappears, the card was not authored — restore the config entry and stop.**

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json
git commit -m "Take secur-financial's card colour from authoring, not site config

The card behind en.card.b7ac2fab now authors textcolor directly, so the
componentStyles entry pinning its colour by id is redundant. The banner
entry stays: a hero's colour has no authoring path in the export yet.

Verified the exported styles.color for that card is unchanged after the
entry was removed."
```

---

### Task 4: retire `linklessCardType` on halliby and carvelo

**Depends on Task 1** and on every affected card authoring `mobilecardstyle: one-line`.

This is the task most likely to cause a visible regression, because `linklessCardType` currently **forces** `image-text-one-line` on every linkless card. Removing it reverts any un-authored card to its `style`-derived type. Do the two sites separately so one can be rolled back without the other.

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/sites/halliby/defaults.json`
- Modify: `core/src/main/resources/edsMobileApp/sites/carvelo/defaults.json`

- [ ] **Step 1: List every card the site config is currently changing**

```bash
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/halliby" \
  | python -c "import sys,json;d=json.load(sys.stdin);\
t=[];\
walk=lambda i:[ (t.append(((c.get('meta') or {}).get('id'),(c.get('content') or {}).get('link'),(c.get('content') or {}).get('cardType'))) if (c.get('meta') or {}).get('component')=='card' else None, walk(c['content']) if isinstance(c.get('content'),list) else None) for c in i];\
[walk(s.get('content') or []) for s in d['screens'].values()];\
print('linkless cards forced to one-line:');[print('  ',x[0]) for x in t if x[1]=='#']"
```

Every id printed needs `mobilecardstyle: one-line` authored before Step 2. Hand this list to whoever authors.

- [ ] **Step 2: Remove the key from halliby only**

Delete the `"linklessCardType": "image-text-one-line"` line. Keep `renderLinklessCards: true` — it is a separate concern (see Scope).

- [ ] **Step 3: Build, deploy, and diff halliby's card types**

```bash
curl -s -u admin:admin "http://localhost:6502/content/contentbridge/api/getPageDetails.json?path=/content/halliby" -o /tmp/halliby-after4.json
python -c "import json;\
g=lambda p:(lambda d: (lambda t: t)([ ((c.get('meta') or {}).get('id'),(c.get('content') or {}).get('cardType')) for s in d['screens'].values() for c in (s.get('content') or []) ]))(json.load(open(p,encoding='utf-8')));\
print('compare the ids from Step 1 by hand — any that moved off image-text-one-line are un-authored')"
```

Expected: every id from Step 1 still reports `image-text-one-line`, now from its own `mobilecardstyle`. **Any id that moved is un-authored — restore the key and go back to authoring.**

- [ ] **Step 4: Commit halliby**

```bash
git add core/src/main/resources/edsMobileApp/sites/halliby/defaults.json
git commit -m "Take halliby's card type from authoring, not site config

Every linkless card on the site now authors mobilecardstyle, so the
site-wide linklessCardType that forced image-text-one-line on all of them
is redundant. renderLinklessCards stays: it decides whether a linkless
card is exported at all, which no authored field expresses.

Verified every card the setting used to affect still exports
image-text-one-line, now from its own authoring."
```

- [ ] **Step 5: Repeat Steps 1–4 for carvelo**

Same commands with `path=/content/carvelo` and `sites/carvelo/defaults.json`. Keep `renderLinklessCards` **and** `screenList` — both are container-level (see Scope). Commit separately, with carvelo named in the message.

---

## Deferred — needs a decision before it can be planned

1. **`custommobilestyle`** ("Custom Mobile Style — custom styles for the card on mobile", free-form string). There is no obvious target in the export contract: `styles` is a fixed key/value map, not a CSS string. Someone has to say what the client should do with it. Until then it stays unread. Do not invent a target.
2. **`overlaybackground`** ("Keep the teaser overlay at 50% opacity. Turn off for a solid black overlay"). This is the card equivalent of the hero's scrim and probably belongs in the same fix as item 3.
3. **The hero `textoverlay` → `opacity` mapping looks inverted.** On the web, `textoverlay: false` sets `background: transparent` **and** `filter: brightness(.65)` on the image (`blocks/hero/hero.css`), i.e. the image is *darkened* so light text stays readable. The mobile rule maps `false` → `opacity: 0`, i.e. no darkening at all. secur-financial's hero exports `opacity: 0`, which is why its white title sits unreadable on a bright photo. Fixing this affects every site's heroes, so it needs its own plan and its own sign-off — but it likely removes the need for the `en.banner.*` `componentStyles` entry entirely, and the hero model also has an unread `color` ("Text Color") field.
4. **Container-level settings have no authoring path** (`renderLinklessCards`, `screenList`, the `componentMeta` row layout). Satisfying the requirement for these needs new fields on the `cards` container model in `migration-parent-repo`, which has no model today. Raise with that repo's owner.

## Risks

| risk | mitigation |
|---|---|
| `cards-specific-changes` is not merged or deployed, so authors cannot set the new fields | Tasks 1–2 are safe to merge regardless (fallback path). Tasks 3–4 are blocked until authoring is possible — do not start them. |
| A bare `333` is authored instead of `#333` | The value passes through unchanged and the client gets an invalid colour. The field description permits `333`. If this happens in practice it needs a `hexColor` type in `FieldRule` — a Java change, out of this plan. |
| Removing a site key regresses an un-authored card | Tasks 3 and 4 each verify the specific ids before and after, and each site is done in its own commit so it can be reverted alone. |
| AEM down, so no task can be verified | Every task's verification depends on 6502. Do not mark complete on a green build alone. |
