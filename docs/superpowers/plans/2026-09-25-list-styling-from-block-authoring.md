# List Styling From Block Authoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a Cards block converts to a `screenList`, let the block's own authoring control the list's styling — the same move already made for cards with `mobilecardstyle` / `mobileimagestyle`, but one level up.

**Architecture:** One resolver with a three-level precedence — **block property → site `screenList` config → compiled-in default** — replacing today's `screenListColor`, which covers only two of the list's eleven values. Every property is read off the block's `ValueMap` exactly as `mobilecardaslist` and `isCardsBlock` already read theirs, so it works whether the value comes from a block model in the parent repo or is set on the node directly.

**Tech Stack:** Java 11 OSGi bundle, JUnit 5 + `io.wcm.testing.aem-mock.junit5`. 22 tests currently pass.

**Spec:** this document.

## Global Constraints

- Branch: cut from `develop` (currently `ac8cdd6`).
- **No parent-repo changes.** The field definitions in Task 5 are documentation for whoever adds them later; this plan does not touch `migration-parent-repo`.
- A block that authors none of these must export a byte-identical `screenList`. Every property falls back to what it emits today.
- These properties apply **only** when the block actually becomes a list. On a block that exports as cards they are inert, and that is correct — card-level styling is `mobilecardstyle` / `mobileimagestyle`.

---

## What the list emits today, and what controls it

From `ComponentTreeWalker.cardsBlockAsList`:

```java
content.put("items", screenKeys);                                    // derived
content.put("layout", "horizontal");                                 // HARDCODED
content.put("listType", "screenList");                               // fixed identity
content.put("textColor", screenListColor(resource, "textColor", "#f4f3f3"));  // site-configurable
content.put("imageWidth", 130);                                      // HARDCODED
content.put("itemHeight", null);                                     // HARDCODED
content.put("itemRadius", null);                                     // HARDCODED
content.put("itemsInRow", screenKeys.size());                        // derived
content.put("itemRenderer", itemRenderer);                           // derived (unanimous cardType)
content.put("filterProperty", "Category");                           // HARDCODED
content.put("imageResizeMode", "cover");                             // HARDCODED

styles.put("gap", 12);                                               // HARDCODED
styles.put("color", screenListColor(resource, "color", "#ffffff"));  // site-configurable
styles.put("marginBottom", "16");                                    // HARDCODED
styles.put("marginHorizontal", "8");                                 // HARDCODED
```

**Eleven presentation values. Two are configurable.** The rest are compiled-in, copied from the LUMA MOBILE reference's own three-card screenList.

### The full property set, prioritised

| priority | block property | type | target | today |
|---|---|---|---|---|
| **P1** | `mobilelistitemsinrow` | number | `content.itemsInRow` | `screenKeys.size()` |
| **P1** | `mobilelistitemrenderer` | select | `content.itemRenderer` | unanimous `cardType`, else `overlay` |
| **P1** | `mobilelisttextcolor` | text | `content.textColor` | site `screenList.textColor`, else `#f4f3f3` |
| **P1** | `mobilelistcolor` | text | `styles.color` | site `screenList.color`, else `#ffffff` |
| **P2** | `mobilelistimagewidth` | number | `content.imageWidth` | `130` |
| **P2** | `mobilelistimageresize` | select | `content.imageResizeMode` | `cover` |
| **P2** | `mobilelistitemheight` | number | `content.itemHeight` | `null` |
| **P2** | `mobilelistitemradius` | number | `content.itemRadius` | `null` |
| **P3** | `mobilelistlayout` | select | `content.layout` | `horizontal` |
| **P3** | `mobilelistgap` | number | `styles.gap` | `12` |
| **P3** | `mobilelistmarginbottom` | text | `styles.marginBottom` | `"16"` |
| **P3** | `mobilelistmarginhorizontal` | text | `styles.marginHorizontal` | `"8"` |
| — | `filterProperty` | — | — | **leave hardcoded**, see below |

**Why `itemsInRow` is P1:** India's block converts to a one-item list, and `itemsInRow` becomes `1`, so the tile renders full width instead of the third-width tile aligned with the rows above it. There is no way to say otherwise today.

**Why `itemRenderer` is P1:** it is derived from the cards agreeing on a `cardType`, and falls back to `overlay` when they disagree — a silent, invisible decision the author cannot override.

**Why `filterProperty` is excluded:** `"Category"` looks like a client-side data contract, not styling. Do not expose it until someone confirms what the client does with it.

### Naming

`mobilelist*`, matching the existing `mobilecardstyle` / `mobileimagestyle` / `mobilecardaslist` convention: `mobile` prefix, all lowercase, no separators. The `mobilecardaslist` property added earlier is the precedent for block-level properties, and these sit beside it.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `core/src/main/java/.../ComponentTreeWalker.java` | `listValue` resolver; apply to each emitted key | 1–4 |
| `core/src/main/java/.../SiteDefaults.java` | javadoc for the widened `screenList` section | 2 |
| `core/src/test/java/.../ListStylingTest.java` | **new** — precedence, and that an unauthored block is unchanged | 1–4 |

---

## Task 1: The resolver, plus `itemsInRow` and `itemRenderer`

**Files:**
- Modify: `core/src/main/java/com/edsMobileApp/core/services/impl/ComponentTreeWalker.java`
- Create: `core/src/test/java/com/edsMobileApp/core/services/impl/ListStylingTest.java`

**Interfaces:**
- Produces: `private Object listValue(Resource block, String property, String siteKey, Object fallback)` — block property, then site `screenList` entry, then fallback.
- Replaces: `screenListColor(Resource, String, String)`, whose two call sites become `listValue` calls.

- [ ] **Step 1: Write the failing test**

```java
@Test
void itemsInRowDefaultsToTheNumberOfItems() {
    // a one-item list is one-across today
    assertEquals(1, content(convert(indiaBlock())).get("itemsInRow"));
}

@Test
void theBlockCanPinItemsInRow() {
    assertEquals(3, content(convert(indiaBlock("mobilelistitemsinrow", 3))).get("itemsInRow"));
}

@Test
void theBlockCanOverrideItemRenderer() {
    assertEquals("overlay",
            content(convert(trioBlock("mobilelistitemrenderer", "overlay"))).get("itemRenderer"));
}

@Test
void anUnauthoredBlockKeepsTheDerivedRenderer() {
    assertEquals("stacked", content(convert(trioBlock())).get("itemRenderer"));
}
```

Build the fixtures on `CardsAsListTest`'s pattern — that class already constructs a walker with `new ComponentTreeWalker(null, new UrlServiceImpl(), null, null, ComponentMappings.load())` and drives `cardsBlockAsList` directly. Reuse it rather than inventing a second harness.

- [ ] **Step 2: Run and confirm the two override tests fail**

```bash
mvn test -pl core -Dtest=ListStylingTest
```

- [ ] **Step 3: Add the resolver**

```java
    /**
     * A screenList presentation value, in precedence order: the block's own {@code mobilelist*}
     * property, then the site's {@code screenList} entry in defaults.json, then the compiled-in
     * default. Blank is treated as unset at every level, so a property authored and then cleared
     * falls back rather than emitting empty.
     *
     * <p>The block property is the narrowest scope and wins: one row of destination tiles can be
     * sized differently from another on the same site. The site entry stays because a colour is
     * usually a site-wide decision - it is what makes a light-background site readable.</p>
     */
    private Object listValue(final Resource block, final String property,
                             final String siteKey, final Object fallback) {
        final Object authored = block.getValueMap().get(property);
        if (authored != null && !(authored instanceof String && StringUtils.isBlank((String) authored))) {
            return authored;
        }
        if (siteDefaults != null && siteKey != null) {
            final Object site = SiteDefaults.objectMap(
                    siteDefaults.forPath(block.getPath()), SiteDefaults.KEY_SCREEN_LIST).get(siteKey);
            if (site instanceof String && StringUtils.isNotBlank((String) site)) {
                return site;
            }
            if (site != null && !(site instanceof String)) {
                return site;
            }
        }
        return fallback;
    }
```

Then:

```java
content.put("itemsInRow", listValue(resource, "mobilelistitemsinrow", "itemsInRow", screenKeys.size()));
content.put("itemRenderer", listValue(resource, "mobilelistitemrenderer", "itemRenderer", itemRenderer));
```

Note `itemRenderer`'s fallback is the *derived* value, so the unanimity rule survives untouched and only an explicit authored value displaces it.

- [ ] **Step 4: Run the tests and confirm they pass**

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/edsMobileApp/core/services/impl/ComponentTreeWalker.java \
        core/src/test/java/com/edsMobileApp/core/services/impl/ListStylingTest.java
git commit -m "Let a Cards block set its list's itemsInRow and itemRenderer"
```

---

## Task 2: Colours, and retiring `screenListColor`

**Files:** `ComponentTreeWalker.java`, `SiteDefaults.java`, `ListStylingTest.java`

- [ ] **Step 1: Write the failing test** — block beats site, site beats default, and a block with neither is unchanged:

```java
@Test
void theBlockColourBeatsTheSiteColour() { /* mobilelisttextcolor wins over screenList.textColor */ }

@Test
void theSiteColourStillAppliesWhenTheBlockSaysNothing() { /* today's behaviour */ }

@Test
void neitherLeavesTheCompiledDefault() {
    assertEquals("#f4f3f3", content(convert(trioBlock())).get("textColor"));
    assertEquals("#ffffff", styles(convert(trioBlock())).get("color"));
}
```

- [ ] **Step 2: Run and confirm the block-override test fails**

- [ ] **Step 3: Replace both `screenListColor` calls**

```java
content.put("textColor", listValue(resource, "mobilelisttextcolor", "textColor", "#f4f3f3"));
styles.put("color", listValue(resource, "mobilelistcolor", "color", "#ffffff"));
```

Delete `screenListColor` — `listValue` subsumes it. Check for other callers first:

```bash
grep -rn "screenListColor" core/src/main
```

Update `SiteDefaults.KEY_SCREEN_LIST`'s javadoc: the section is no longer colour-only, and a block property now outranks it.

- [ ] **Step 4: Run the full suite** — `mvn test -pl core`, expecting the 22 existing plus the new ones.

- [ ] **Step 5: Commit**

---

## Task 3: Image and item geometry (P2)

**Files:** `ComponentTreeWalker.java`, `ListStylingTest.java`

- [ ] **Step 1: Write the failing tests** — one per property, each asserting the default is unchanged when unauthored.

- [ ] **Step 2: Run and confirm they fail**

- [ ] **Step 3: Apply**

```java
content.put("imageWidth", listValue(resource, "mobilelistimagewidth", "imageWidth", 130));
content.put("imageResizeMode", listValue(resource, "mobilelistimageresize", "imageResizeMode", "cover"));
content.put("itemHeight", listValue(resource, "mobilelistitemheight", "itemHeight", null));
content.put("itemRadius", listValue(resource, "mobilelistitemradius", "itemRadius", null));
```

`itemHeight` and `itemRadius` default to `null` and the client expects the keys present, so `listValue` must return `null` rather than omitting — it does, since `null` is the fallback and an absent property falls through to it.

- [ ] **Step 4: Run the tests**

- [ ] **Step 5: Commit**

---

## Task 4: Spacing and layout (P3)

**Files:** `ComponentTreeWalker.java`, `ListStylingTest.java`

- [ ] **Step 1: Write the failing tests**

- [ ] **Step 2: Run and confirm they fail**

- [ ] **Step 3: Apply**

```java
content.put("layout", listValue(resource, "mobilelistlayout", "layout", "horizontal"));
styles.put("gap", listValue(resource, "mobilelistgap", "gap", 12));
styles.put("marginBottom", listValue(resource, "mobilelistmarginbottom", "marginBottom", "16"));
styles.put("marginHorizontal", listValue(resource, "mobilelistmarginhorizontal", "marginHorizontal", "8"));
```

Leave `filterProperty` and `listType` alone.

- [ ] **Step 4: Run the full suite**

- [ ] **Step 5: Commit**

---

## Task 5: Field definitions for the parent repo (documentation only)

**Files:** none in this repo. **Do not edit `migration-parent-repo`.**

The Cards block has no model — only `template: { name, filter }`, with `card` the sole model. So none of these are authorable in the Universal Editor until a block-level model exists. Hand this to whoever owns that change:

```json
{
  "id": "cards",
  "fields": [
    { "component": "tab", "label": "Mobile Specific", "name": "mobileSpecific" },
    { "component": "boolean", "name": "mobilecardaslist", "label": "Render as list on mobile",
      "value": "false", "valueType": "boolean" },
    { "component": "number", "name": "mobilelistitemsinrow", "label": "Items per row", "value": "" },
    { "component": "select", "name": "mobilelistitemrenderer", "label": "Item style", "value": "",
      "options": [ { "name": "Stacked", "value": "stacked" },
                   { "name": "Overlay", "value": "overlay" },
                   { "name": "Image and text in one line", "value": "image-text-one-line" },
                   { "name": "Image partially overlayed", "value": "partial-overlay" } ] },
    { "component": "text", "name": "mobilelisttextcolor", "label": "Item label color", "value": "" },
    { "component": "text", "name": "mobilelistcolor", "label": "List color", "value": "" },
    { "component": "number", "name": "mobilelistimagewidth", "label": "Item image width", "value": "" },
    { "component": "select", "name": "mobilelistimageresize", "label": "Item image resize", "value": "",
      "options": [ { "name": "Cover", "value": "cover" }, { "name": "Contain", "value": "contain" } ] },
    { "component": "number", "name": "mobilelistitemheight", "label": "Item height", "value": "" },
    { "component": "number", "name": "mobilelistitemradius", "label": "Item corner radius", "value": "" },
    { "component": "select", "name": "mobilelistlayout", "label": "List layout", "value": "",
      "options": [ { "name": "Horizontal", "value": "horizontal" },
                   { "name": "Vertical", "value": "vertical" } ] },
    { "component": "number", "name": "mobilelistgap", "label": "Gap", "value": "" },
    { "component": "text", "name": "mobilelistmarginbottom", "label": "Margin bottom", "value": "" },
    { "component": "text", "name": "mobilelistmarginhorizontal", "label": "Margin horizontal", "value": "" }
  ]
}
```

plus `"model": "cards"` on the Cards block definition. Every field defaults to empty so an untouched block keeps today's output.

Until that lands, the properties are settable on the block node in CRXDE and the export reads them identically.

---

## Task 6: Verify against the live export

- [ ] **Step 1: Baseline all sites before deploying.**
- [ ] **Step 2: Deploy and diff — every site must be unchanged**, since no block authors any of these yet. This is the whole safety claim of the plan.
- [ ] **Step 3: Author one property and confirm.** Set `mobilelistitemsinrow = 3` on India's block and check `itemsInRow` flips from `1` to `3` — the tile should then align with the three-across rows above it.

---

## Open Questions

1. **Should `itemsInRow` default to 3 rather than the item count?** India's one-item list rendering full-width is the reported annoyance. Pinning the default to 3 would fix it estate-wide with no authoring, but changes every existing list whose count is not 3 — so it is a behaviour change, not a config addition, and belongs in its own decision.
2. **Is `filterProperty: "Category"` styling or a data contract?** Excluded here on the assumption it is the latter. If it is presentation, it joins P3.
3. **Should the site-level `screenList` section accept the full key set too**, not just the two colours? `listValue` already supports it — the site key is passed for every property. Confirm that is wanted, since it widens `defaults.json`'s surface.
4. **`itemHeight` / `itemRadius` are `null` today.** Nobody has set them, so it is unverified that the client honours them. Worth one authored test before promising them as knobs.

---

## Self-Review

- **Spec coverage.** The request was block-level properties controlling list styling, mirroring the card work → Tasks 1–4, prioritised. "What other values need configuring" → the eleven-row table, with `filterProperty` and `listType` explicitly excluded and why.
- **Placeholders.** Task 1 Step 1 points at `CardsAsListTest` for the fixture pattern rather than restating it; that class exists and already drives `cardsBlockAsList` directly.
- **Type consistency.** `listValue` returns `Object` because the targets are mixed — `int` (`itemsInRow`, `imageWidth`, `gap`), `String` (colours, margins), and `null` (`itemHeight`, `itemRadius`). Numbers authored in JCR arrive as `Long`; the client tolerates that, but Task 3 Step 4 should confirm the emitted JSON is a number and not a quoted string.
