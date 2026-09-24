# Card Config From Authoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive each card's mobile presentation from the card's own authored `mobileSpecific` fields instead of from per-site entries in `defaults.json`.

**Architecture:** The card projection is a declarative rule file (`mappings/card.json`) read by `ComponentMapping`. Adding a second `FieldRule` on an already-targeted key layers a new source over the old one: rules run in declaration order and `resolveInto` only writes when the resolved value `isPresent`, so a rule with `from` and no `default` is a no-op when the property is unauthored. That gives "mobile authoring wins, web `style` remains the fallback" without touching Java. **No Java changes at all** — the whole mapping change is two rules in one JSON file.

**Scope — card-to-card only.** `mobileimagestyle` → `imageResize` and `mobilecardstyle` → `cardType`, on the card-to-card projection. The multi-card-to-list path (`cardsBlockAsList`, a three-card block exported as a `screenList`) is **out of scope and must not be edited**.

**Tech Stack:** Java 11 OSGi bundle, Jackson, JUnit 5 + `io.wcm.testing.aem-mock.junit5` (both already declared in `core/pom.xml`; `core/src/test` does not exist yet and is created by Task 1).

**Spec:** the `mobileSpecific` tab in `blocks/cards/_cards.json` on parent repo branch `cards-specific-changes` (`D:\Adobe-EDS Migration\parent-repo\worksapce\migration-parent-repo\blocks\cards\_cards.json`, commit `6a58d36`).

## Global Constraints

- Branch: `card-config` (cut from `main`, develop merged in at `b14d000`). Do not start from `main`.
- Authored field names are fixed by the parent repo and are lowercase, no separators: `mobilecardstyle`, `mobileimagestyle`, `mobilebuttoneventtype`, `custommobilestyle`, `textcolor`.
- `mobilecardstyle` values: `one-line`, `overlay`, `below`, `partial-overlay`. Model default `below`.
- `mobileimagestyle` values: `contain`, `cover`. Model default `""`.
- Client `cardType` values: `stacked`, `image-text-one-line`, `overlay`, `partial-overlay`.
- A site that authors nothing must export byte-identical JSON to today. Every rule added is additive and writes only when its source is authored.
- Config removal (Tasks 4 and 5) happens only after Tasks 1–2 are deployed and verified against the live author. Deleting the working path before the replacement is proven leaves nothing to fall back to.
- Do not edit `ComponentTreeWalker.java`. The multi-card-to-list path is out of scope.

---

## Evidence this plan is built on

Read before starting; each claim was checked on `card-config` at `b14d000`.

| Claim | Where |
|---|---|
| Rules on the same target layer, later wins when present | `ComponentMapping.java:425` `resolveInto(... isPresent(value) ? ... )` |
| A rule with `from` and no `default` writes nothing when blank | `FieldRule.java:348` `resolve()` → `blankToNull` |
| `map` with no `"*"` passes an unmatched value through unchanged | `FieldRule` value-map handling |
| Cards are mapped with an **empty** parent source | `ComponentTreeWalker.java:764,794,888` `apply(authored, Collections.emptyMap())` |
| Panel layout comes only from resource type, never authoring | `ComponentTreeWalker.java:1355-1358` `layoutOf(resourceType)` |
| A 3-card block can become a `screenList`, and `itemRenderer` already derives from the cards' `cardType` | `ComponentTreeWalker.java:877-907` `cardsBlockAsList` — **out of scope, do not edit** |
| `screenList` colours come from site config | `ComponentTreeWalker.java:822-829` `screenListColor` — **stays as it is** |
| `imageResize` already reads `mobileimagestyle` | `mappings/card.json` — **already done on develop, no task needed** |

### Target contract

The reference card the export must produce:

```json
"meta":    { "id": "DzRtlev_Nj", "order": 2, "component": "card" },
"styles":  { "padding": "8" },
"content": { "image": "...", "title": "Check in", "cardType": "stacked",
             "subtitle": "...", "imgHeight": 120, "imageResize": "cover",
             "titleVariant": "titleMedium", "subtitleVariant": "titleSmall",
             "titleFontWeight": "bold", "subtitleFontWeight": "regular" }
```

Only `cardType` and `imageResize` are in scope. `imgHeight`, `styles.padding` and the four variant/weight keys are **not** part of this work — see Open Question 4 for the divergence they show.

### What can move to authoring, and what cannot

**Can move:**

| Site config today | Becomes |
|---|---|
| `linklessCardType` (halliby, carvelo) | `mobilecardstyle` per card |
| `componentStyles."en.card.b7ac2fab"` (secur-financial) | `textcolor` on that card |

**Cannot move — and why:**

- **`screenList.textColor` / `.color` (wknd-fly, carvelo).** Excluded by scope: the multi-card-to-list path stays unchanged. These entries **stay in `defaults.json`**.

- **`componentMeta."wknd-club.panel.b1059ca7"` (`layout: horizontal`, `wrapOverflowing`).** A panel's layout is decided by `layoutOf(resourceType)` — `columns` is horizontal, everything else vertical. There is no block-level field in `_cards.json` (the `cards` definition carries only a `filter`, no model), and cards are mapped with `Collections.emptyMap()` as parent source, so `parentFrom` cannot reach a block property even if one existed. This entry **stays in `defaults.json`** unless you add a block-level field upstream. See Open Questions.
- **`componentStyles."en.banner.65721cdd"` (secur-financial).** A banner, not a card. Out of scope.
- **`mobilebuttoneventtype` on a card.** Already consumed by `button.json`, `flight-search.json`, `flights.json`. The card contract has no `eventType` target and inventing one risks emitting a key the client ignores. See Open Questions.
- **`custommobilestyle`.** Free-text with no agreed semantics. See Open Questions.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `core/src/main/resources/edsMobileApp/mappings/card.json` | card projection; gains two rules | 1, 2 |
| `core/src/test/java/com/edsMobileApp/core/services/impl/CardMappingTest.java` | **new** — asserts the layering, including the unauthored no-op | 1, 2 |
| `core/src/main/resources/edsMobileApp/sites/{halliby,carvelo}/defaults.json` | drop `linklessCardType` | 4 |
| `core/src/main/java/com/edsMobileApp/core/services/impl/{SiteDefaults,PageJsonExporterImpl}.java` | drop `KEY_LINKLESS_CARD_TYPE` + `applyLinklessCardFormat` | 4 |
| `core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json` | drop the card entry from `componentStyles` | 5 |

`ComponentTreeWalker.java` is **not** in this list. The multi-card-to-list path lives there and is out of scope.

The test file is one file for all mapping assertions: these are two facets of one rule file, they change together, and splitting them would mean two copies of the same registry setup.

---

## Task 1: cardType from mobilecardstyle

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/mappings/card.json` (the `cardType` rule)
- Create: `core/src/test/java/com/edsMobileApp/core/services/impl/CardMappingTest.java`

**Interfaces:**
- Consumes: `ComponentMappings.load()`, `ComponentMappings.find(String)`, `ComponentMapping.effectiveFor(Map)`, `ComponentMapping.apply(Map, Map)` — all package-private, so the test must live in `com.edsMobileApp.core.services.impl`.
- Produces: `content.cardType` ∈ {`stacked`, `image-text-one-line`, `overlay`, `partial-overlay`}.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/edsMobileApp/core/services/impl/CardMappingTest.java`:

```java
package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CardMappingTest {

    private ComponentMapping card;

    @BeforeEach
    void setUp() {
        card = ComponentMappings.load().find("card");
    }

    private Map<String, Object> map(final Map<String, Object> authored) {
        return card.effectiveFor(authored).apply(authored, Map.of());
    }

    private static Map<String, Object> authored(final String... keyValue) {
        final Map<String, Object> out = new HashMap<>();
        // every card needs an image, otherwise the rule's "requires" sends it to the htmlText fallback
        out.put("image", "/content/dam/x.jpg");
        out.put("text", "<h2>Title</h2>");
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put(keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

    @Test
    void mobileCardStyleWinsOverWebStyle() {
        assertEquals("image-text-one-line",
                map(authored("style", "teaser-card", "mobilecardstyle", "one-line")).get("cardType"));
        assertEquals("overlay",
                map(authored("style", "image-top", "mobilecardstyle", "overlay")).get("cardType"));
        assertEquals("stacked",
                map(authored("style", "teaser-card", "mobilecardstyle", "below")).get("cardType"));
        assertEquals("partial-overlay",
                map(authored("style", "", "mobilecardstyle", "partial-overlay")).get("cardType"));
    }

    @Test
    void unauthoredMobileCardStyleLeavesWebStyleUntouched() {
        assertEquals("overlay", map(authored("style", "teaser-card")).get("cardType"));
        assertEquals("partial-overlay", map(authored("style", "teaser-overlay")).get("cardType"));
        assertEquals("image-text-one-line", map(authored("style", "image-left")).get("cardType"));
        assertEquals("stacked", map(authored("style", "image-top")).get("cardType"));
        assertEquals("stacked", map(authored()).get("cardType"));
    }

    @Test
    void blankMobileCardStyleIsTreatedAsUnauthored() {
        assertEquals("overlay",
                map(authored("style", "teaser-card", "mobilecardstyle", "")).get("cardType"));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
mvn test -pl core -Dtest=CardMappingTest
```

Expected: `mobileCardStyleWinsOverWebStyle` fails — `cardType` is still derived from `style` alone, so the first assertion gets `overlay` where `image-text-one-line` is wanted. The other two tests should already pass; they are the regression guard.

- [ ] **Step 3: Add the second rule**

In `card.json`, leave the existing `style` rule exactly as it is and add one rule **immediately after it**. Order is the whole mechanism — the new rule must come second so it overwrites.

```json
    { "target": "cardType", "from": "style",
      "map": {
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
    { "target": "cardType", "from": "mobilecardstyle",
      "map": {
        "one-line": "image-text-one-line",
        "overlay": "overlay",
        "below": "stacked",
        "partial-overlay": "partial-overlay",
        "*": "stacked"
      } },
```

Two things that are deliberate and must not be "tidied":

- **No `default` on the second rule.** A `default` would make it fire for every card and erase the `style` fallback for the whole estate.
- **`"*": "stacked"`** is required. Without a `"*"` entry an unmatched value passes through *unchanged*, which would emit a raw authoring token (e.g. `some-new-option`) as a `cardType` the client does not understand. `stacked` matches the model's own `below` default, so an unknown future option degrades to the safe layout.

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
mvn test -pl core -Dtest=CardMappingTest
```

Expected: all three PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/edsMobileApp/mappings/card.json core/src/test/java/com/edsMobileApp/core/services/impl/CardMappingTest.java
git commit -m "Derive a card's mobile cardType from its own mobilecardstyle"
```

---

## Task 2: styles.color from textcolor

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/mappings/card.json` (the `styles` block)
- Modify: `core/src/test/java/com/edsMobileApp/core/services/impl/CardMappingTest.java`

**Interfaces:**
- Consumes: `ComponentMapping.applyStyles(Map, Map)` (package-private).
- Produces: `styles.color` — a hex string, or absent.

- [ ] **Step 1: Write the failing test**

Append to `CardMappingTest`:

```java
    private Map<String, Object> styles(final Map<String, Object> authored) {
        return card.effectiveFor(authored).applyStyles(authored, Map.of());
    }

    @Test
    void textColorWinsOverTheStyleDerivedColour() {
        assertEquals("#1a1a1a",
                styles(authored("style", "teaser-card", "textcolor", "#1a1a1a")).get("color"));
    }

    @Test
    void unauthoredTextColorLeavesTheStyleDerivedColour() {
        assertEquals("#ffffff", styles(authored("style", "teaser-card")).get("color"));
        assertEquals("#ffffff", styles(authored("style", "teaser-overlay")).get("color"));
        assertEquals("", styles(authored("style", "image-top")).get("color"));
    }

    @Test
    void anUnknownStyleDoesNotLeakIntoTheColour() {
        assertEquals("", styles(authored("style", "some-future-token")).get("color"));
    }
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
mvn test -pl core -Dtest=CardMappingTest
```

Expected: `textColorWinsOverTheStyleDerivedColour` fails with `#ffffff` — nothing reads `textcolor` yet. The other two pass.

- [ ] **Step 3: Add the second styles rule**

Replace the `styles` block in `card.json` with:

```json
  "styles": [
    { "target": "color", "from": "style",
      "map": { "teaser-overlay": "#ffffff", "teaser-card": "#ffffff", "*": "" }, "nullable": true },
    { "target": "color", "from": "textcolor", "nullable": true }
  ]
```

`anUnknownStyleDoesNotLeakIntoTheColour` is the test that matters here: an earlier attempt used a single rule with `first: ["textcolor", "style"]`, which let an unknown `style` token through into `styles.color` as a bogus colour. Two rules on one target is what keeps the two sources' value spaces separate.

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
mvn test -pl core -Dtest=CardMappingTest
```

Expected: all six PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/edsMobileApp/mappings/card.json core/src/test/java/com/edsMobileApp/core/services/impl/CardMappingTest.java
git commit -m "Derive a card's text colour from its own textcolor field"
```

---

## Task 3: Deploy and verify against the live author

**Files:** none — this is the gate that Tasks 4 and 5 depend on.

- [ ] **Step 1: Confirm which branch Cloud Manager builds**

Ask; do not assume. If the pipeline builds `develop` or `main`, merge `card-config` there first. Nothing below can be checked until the bundle is on `p35437`.

- [ ] **Step 2: Capture a baseline before the deploy**

For each of `wknd-fly`, `secur-financial`, `halliby`, `carvelo`, save the export:

```bash
curl -s "https://author-p35437-e2207568.adobeaemcloud.com/content/contentbridge/api/getPageDetails.json?path=/content/<site>/language-masters/en" > baseline-<site>.json
```

(wknd-fly is at `/content/wknd-fly`, not under `language-masters`.)

- [ ] **Step 3: Deploy, then re-capture and diff**

Every site whose cards are **not** authored with `mobileSpecific` fields must diff empty. wknd-fly's 26 authored cards should be the only difference, and only in `cardType` / `imageResize`.

- [ ] **Step 4: Check the 26 authored wknd-fly cards specifically**

3 of them carry `mobileimagestyle: contain`; the rest carry only `mobilecardstyle`. Confirm `imageResize` is `contain` on exactly those 3 and `cover` elsewhere.

- [ ] **Step 5: Watch for the Universal Editor default-persist trap**

`mobilecardstyle` has a model default of `below`. If the editor writes that default into JCR on save rather than leaving the property absent, every saved card starts claiming `stacked` and the `style` fallback stops being reachable. Check a card that was saved without touching the Mobile Specific tab: `mobilecardstyle` should be **absent** from its node in CRXDE, not `below`. If it is present, stop and re-plan Task 1 — the layering assumption does not hold.

---

## Task 4: Retire `linklessCardType`

**Blocked on Task 3.** Only start once the deploy shows cards deriving their own type.

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/sites/halliby/defaults.json`
- Modify: `core/src/main/resources/edsMobileApp/sites/carvelo/defaults.json`
- Modify: `core/src/main/java/com/edsMobileApp/core/services/impl/SiteDefaults.java` (`KEY_LINKLESS_CARD_TYPE`, line 114, and its javadoc)
- Modify: `core/src/main/java/com/edsMobileApp/core/services/impl/PageJsonExporterImpl.java` (`applyLinklessCardFormat`, around line 840, and its call site)

- [ ] **Step 1: Author the cards first**

Both sites use `"linklessCardType": "image-text-one-line"`, which forces that type on every card whose `link` is `"#"`. Before deleting it, set `mobilecardstyle: one-line` on each of those cards on the author instance. Find them: they are `component: card` with `content.link == "#"` in the site's export.

- [ ] **Step 2: Verify the export is unchanged with the config still in place**

With both the authoring and the config present, the export must be identical to baseline — the config and the authoring agree, so this proves the authoring covers every affected card before anything is removed.

- [ ] **Step 3: Remove the config from both sites**

Delete the `"linklessCardType": "image-text-one-line"` line from each `defaults.json`. In halliby's, also remove the trailing comma left on `"renderLinklessCards": true`.

- [ ] **Step 4: Remove the code**

Delete `KEY_LINKLESS_CARD_TYPE` and its javadoc from `SiteDefaults`, delete `applyLinklessCardFormat` from `PageJsonExporterImpl`, and delete its call site. Note there is a dangling javadoc block above `applyLinklessCardFormat` documenting `linklessCardsInRow` (a key that no longer exists) — remove that too while you are in there.

```bash
grep -rn "linklessCardType\|applyLinklessCardFormat\|linklessCardsInRow" core/src/main
```

Expected: no matches.

- [ ] **Step 5: Build, deploy, diff**

```bash
mvn install -pl core
```

Re-export halliby and carvelo. Both must match the Step 2 capture exactly.

- [ ] **Step 6: Commit**

```bash
git add core/src/main core/src/main/resources/edsMobileApp/sites
git commit -m "Retire linklessCardType now cards carry their own mobile type"
```

---

## Task 5: Retire the secur-financial card style override

**Blocked on Task 3.**

**Files:**
- Modify: `core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json`

- [ ] **Step 1: Author `textcolor` on the card**

The entry is:

```json
    "en.card.b7ac2fab": {
      "color": "#FFFFFF"
    }
```

`b7ac2fab` is `Integer.toHexString(path.hashCode())` over that card's JCR path. Find it by exporting `/content/secur-financial/language-masters/en` and locating the component whose `meta.id` is `b7ac2fab`; its position in the tree tells you which authored card it is. Set `textcolor: #FFFFFF` on it in the author.

Note: secur-financial's 26 cards previously failed to author — every POST returned `409 Conflict`, cause unresolved. Sort that out before starting, or this task cannot proceed.

- [ ] **Step 2: Verify the export is unchanged with the config still in place**

`styles.color` on that card must still be `#FFFFFF`, now coming from two sources that agree.

- [ ] **Step 3: Remove the card entry, keep the banner one**

```json
  "componentStyles": {
    "en.banner.65721cdd": {
      "color": "#333333"
    }
  }
```

The banner entry stays — a banner is not a card and has no authoring path. Do **not** remove `KEY_COMPONENT_STYLES` or `applyComponentStyles`; both are still needed for it.

- [ ] **Step 4: Build, deploy, diff**

The export must be identical to the Step 2 capture.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/edsMobileApp/sites/secur-financial/defaults.json
git commit -m "Retire the secur-financial card colour override now the card carries it"
```

---

## Open Questions — resolve before extending scope

These are the parts of the request that have no answer in the current field set. Each needs a decision, not an implementation guess.

1. **Row layout (`componentMeta` on wknd-club).** `layout: horizontal` + `wrapOverflowing: true` currently pins the WKND Club panel. Nothing authored can reach a panel: layout comes from `layoutOf(resourceType)` and cards get an empty parent source. Three options — (a) keep `componentMeta` for this, which is honest and costs one config entry; (b) add a block-level field to the `cards` definition in the parent repo, which is the clean fix and needs an upstream change; (c) treat a token in every card's `custommobilestyle` as the signal, which works with today's fields but spreads one block-level decision across N cards. **Recommendation: (b) if you control the parent repo cadence, otherwise (a).**

2. **`custommobilestyle`.** Free text, no defined semantics. What should a value do — a class name the client resolves, a set of style keys, something else? Until this is answered it cannot be mapped.

3. **`mobilebuttoneventtype` on a card.** Already read by `button.json`, `flight-search.json` and `flights.json`. A card has no `eventType` in its contract. Does the client read one on a card? If not, mapping it would emit a key nobody consumes.

4. **The reference card diverges from what the mapping emits today.** Comparing the reference in Target Contract against `card.json`:

   | Key | Reference | `card.json` emits |
   |---|---|---|
   | `titleVariant` | `titleMedium` | `titleLarge` |
   | `subtitleVariant` | `titleSmall` | `titleMedium` |
   | `titleFontWeight` | `bold` | `regular` |
   | `imgHeight` | `120` | not emitted |
   | `styles.padding` | `"8"` | not emitted |

   Four `const` rules and two absent keys. This is **not** part of the current request and no task touches it — but if the reference is the contract the client actually renders against, every card the export produces is currently off on typography and has no image height. Worth confirming separately whether the reference is normative or just one example screen's authored values.

---

## Self-Review

- **Spec coverage.** `mobilecardstyle` → Task 1. `mobileimagestyle` → already on develop, stated in Evidence with no task. `textcolor` → Task 2. `screenList` and `componentMeta` examples from the request → both excluded, each with its reason (scope, and no authoring path respectively). `mobilebuttoneventtype` and `custommobilestyle` → Open Questions 3 and 2. Reference-card divergence → Open Question 4.
- **Placeholders.** None. Every step carries its actual content; the only "go read the code" instruction was in the removed screenList task.
- **Type consistency.** `cardType` values agree between Task 1's map and Task 4's authoring step (`one-line` → `image-text-one-line`). The `authored(...)` test helper is defined once in Task 1 and reused in Task 2. No task references a file outside the File Structure table.
