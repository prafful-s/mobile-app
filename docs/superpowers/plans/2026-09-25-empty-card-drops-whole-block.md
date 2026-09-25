# An Empty Card Should Not Drop Its Siblings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Cards block with one authored card and two empty placeholder cards exports the authored card, instead of exporting nothing at all.

**Architecture:** Two changes in `ComponentTreeWalker`, both driven by one new predicate. An *empty* card — no image and no text — is not content, so it is excluded from the three-card shape test that gates the screenList conversion, and it is skipped rather than exported unmapped. Nothing else about the cards path changes: the screenList conversion, the unanimity rules and the no-mixed-live-and-dead-cards guarantee all keep their current behaviour for cards that actually carry content.

**Tech Stack:** Java 11 OSGi bundle, JUnit 5 + `io.wcm.testing.aem-mock.junit5` (`core/src/test` exists; 15 tests currently pass).

**Spec:** this document. Reported against `/content/wknd-fly/language-masters/en/destinations`, block `block_1745488606655__2099317153`.

## Global Constraints

- Branch: cut from `card-config` (currently `41aee80`).
- A site that authors no empty cards must export byte-identical JSON. The predicate only fires on a card with neither image nor text.
- Do not change the screenList conversion, `itemRenderer` unanimity, `renderLinklessCards`, or the mixed live/dead drop for cards that carry content. Those are deliberate and unrelated.

---

## Root Cause

The block has three card children. Only one is authored:

| child | image | text | link |
|---|---|---|---|
| `item` | ✅ | ✅ `<h2>India</h2>…` | ✅ `/destinations/india` |
| `item_810748578` | ❌ | ❌ | ❌ |
| `item_1991735907` | ❌ | ❌ | ❌ |

The two empty ones carry only `selectable`, `imagestyle`, `customstyles`, `model`, `modelFields` — styling defaults and bookkeeping, no content.

What the walker does with that, in order:

1. **`isCardsBlock`** — the block's `filter` is `cards`. True.
2. **`cardItemsOf`** — exactly three non-system children, every one resolving to component name `card`. Returns all three, so `isCardsBlockEligibleForList` is **true**.
3. **`cardsBlockAsList`** — requires *every* card's mapped `link` to be a known screen key. `india` is one; the two empty cards have no link, so the card rule's `default: "#"` applies and `"#"` is not a screen key. Returns `null`. No screenList.
4. **The drop guard** (`ComponentTreeWalker` ~line 580):

   ```java
   if (isCardsBlockEligibleForList(child)
           && !hasUnresolvedRealCardLink(child, knownScreenKeys)) {
       if (!(rendersLinklessCards(child) && allCardLinksDead(child))) {
           continue;      // <- the whole block, India included
       }
   }
   ```

   `hasUnresolvedRealCardLink` is true only when *every* card has a real link and none resolves to a screen. Here one resolves and two have no link at all, so it returns **false**, the guard fires, and `continue` discards the block.
5. The `renderLinklessCards` escape hatch does not apply: it requires `allCardLinksDead`, and wknd-fly does not set the flag anyway.

So the block is dropped **by design** — the guarantee is "a client never sees a mix of live and dead cards". The guarantee is sound. What it did not anticipate is an **empty** card. The guard was written for a card that has content but nowhere to go; it treats a card with *nothing authored at all* as if it were such a card, and lets two empty slots veto a real one.

This is also why the web is unaffected — the screenshot shows India rendering fine in the grid. The web block simply renders nothing for an empty item; only the mobile export has a shape test that the empty items corrupt.

### Why the fix is not "relax the guard"

Removing the mixed-drop would let genuinely dead cards through, which is the thing it was added to prevent. The correct fix is narrower: an empty card is not a card for the purposes of that decision. Exclude it, and this block is no longer a three-card group at all — it is a one-card block, which falls through to ordinary card export, exactly as the web treats it.

### Second defect, found while tracing

If the empty cards do reach the normal path, they are **not** dropped. `ComponentMapping.effectiveFor` sends a card with no image to the `htmlText` fallback, whose only field is `text` — also absent — so `mapped.isEmpty()` is true, and the walker then takes this branch:

```java
LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped", authoredName, child.getPath());
```

"Exported unmapped" means a component carrying the raw authored properties — `selectable`, `imagestyle`, `customstyles`. So fixing only the gate would turn one invisible bug into two junk components. Both parts are needed.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `core/src/main/java/.../ComponentTreeWalker.java` | the `isEmptyCard` predicate; exclude from `cardItemsOf`; skip in `collectComponents` | 1, 2 |
| `core/src/test/java/.../EmptyCardTest.java` | **new** — the wknd-fly shape, and the regressions that must not move | 1, 2 |

---

## Task 1: Empty cards do not count toward the three-card shape

**Files:**
- Modify: `core/src/main/java/com/edsMobileApp/core/services/impl/ComponentTreeWalker.java` (`cardItemsOf`, ~line 836)
- Create: `core/src/test/java/com/edsMobileApp/core/services/impl/EmptyCardTest.java`

**Interfaces:**
- Produces: `private boolean isEmptyCard(Resource)` — true when a `card`-modelled resource has neither an image nor text.
- Consumed by: `cardItemsOf`, and therefore by `isCardsBlockEligibleForList`, `hasUnresolvedRealCardLink` and `cardsBlockAsList`, all of which route through it.

- [ ] **Step 1: Write the failing test**

Create `EmptyCardTest`. Build the wknd-fly shape: a `cards` block with one authored card and two empty ones, plus a destination page so the link resolves.

```java
package com.edsMobileApp.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class EmptyCardTest {

    private final AemContext context = new AemContext();

    private Resource cardsBlockWithOneAuthoredCard() {
        context.create().resource("/content/s/en/destinations/jcr:content/root/block",
                "filter", "cards");
        context.create().resource("/content/s/en/destinations/jcr:content/root/block/item",
                "model", "card",
                "image", "/content/dam/s/india.jpg",
                "text", "<h2>India</h2><p>A land of rich cultural heritage.</p>",
                "link", "/content/s/en/destinations/india");
        // two placeholders an author left untouched - styling defaults only, no content
        context.create().resource("/content/s/en/destinations/jcr:content/root/block/item_2",
                "model", "card", "selectable", "false", "imagestyle", "");
        context.create().resource("/content/s/en/destinations/jcr:content/root/block/item_3",
                "model", "card", "selectable", "false", "imagestyle", "");
        return context.resourceResolver().getResource("/content/s/en/destinations/jcr:content/root/block");
    }

    @Test
    void theAuthoredCardSurvivesTwoEmptySiblings() {
        final List<Map<String, Object>> exported = walk(cardsBlockWithOneAuthoredCard());
        assertEquals(1, exported.size(), "the authored card must be exported");
        assertEquals("card", meta(exported.get(0)).get("component"));
    }
}
```

**Note for the implementer:** `ComponentTreeWalker` is constructed with `(modelFactory, urlService, formSchemaService, styleResolver, mappings)` — see `PageJsonExporterImpl`'s constructor. Write a small `walk(Resource)` helper in the test that builds one and calls `componentsOf`, and a `meta(Map)` helper like the one in `FormBlockBuilderTest`. If wiring a real `ModelFactory` through aem-mock proves awkward, drop to testing `cardItemsOf` and `isEmptyCard` directly — they are package-private and that still pins the behaviour this task changes. Do not spend more than one attempt on the full walk before falling back.

- [ ] **Step 2: Run it and confirm it fails**

```bash
mvn test -pl core -Dtest=EmptyCardTest
```

Expected: FAIL with 0 components exported — the block is dropped, which is the reported bug.

- [ ] **Step 3: Add the predicate and use it in `cardItemsOf`**

```java
    /**
     * True when a card has nothing authored to show: no image and no text. Such a card is a
     * placeholder an author left untouched, not content - the web block renders nothing for it,
     * and it must not count toward the three-card shape that gates the screenList conversion.
     * Without this, two empty placeholders beside one authored card make the block look like a
     * three-card group whose links are a live/dead mix, and the guard in collectComponents drops
     * the whole block - the authored card with it.
     *
     * <p>A card with text but no image is NOT empty: it is content, and the card rule's own
     * {@code requires}/{@code fallback} already exports it as {@code htmlText}.</p>
     */
    private boolean isEmptyCard(final Resource item) {
        final Map<String, Object> authored = new GenericComponent(item, urlService).getContent();
        return isBlankValue(authored.get("image"))
                && isBlankValue(authored.get("fileReference"))
                && isBlankValue(authored.get("imageRef"))
                && isBlankValue(authored.get("text"));
    }

    private static boolean isBlankValue(final Object value) {
        return value == null || (value instanceof String && StringUtils.isBlank((String) value));
    }
```

The image property names match the card rule's own `first: ["image", "fileReference", "imageRef"]`, so the predicate and the rule agree on what "has an image" means.

Then in `cardItemsOf`, skip empty cards alongside system nodes:

```java
        for (final Resource child : resource.getChildren()) {
            if (isSystemNode(child)) {
                continue;
            }
            items.add(child);
        }
```

becomes

```java
        for (final Resource child : resource.getChildren()) {
            if (isSystemNode(child) || isEmptyCard(child)) {
                continue;
            }
            items.add(child);
        }
```

With the two placeholders excluded, the block has one item, `items.size() != 3` returns `null`, `isCardsBlockEligibleForList` is false, the guard never fires, and the block falls through to ordinary card export.

- [ ] **Step 4: Run the test and confirm it passes**

```bash
mvn test -pl core -Dtest=EmptyCardTest
```

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/edsMobileApp/core/services/impl/ComponentTreeWalker.java \
        core/src/test/java/com/edsMobileApp/core/services/impl/EmptyCardTest.java
git commit -m "Stop an empty placeholder card dropping its authored siblings"
```

---

## Task 2: An empty card exports nothing

**Files:**
- Modify: `core/src/main/java/com/edsMobileApp/core/services/impl/ComponentTreeWalker.java` (`collectComponents`)
- Modify: `core/src/test/java/com/edsMobileApp/core/services/impl/EmptyCardTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void anEmptyCardIsNotExportedAtAll() {
        final List<Map<String, Object>> exported = walk(cardsBlockWithOneAuthoredCard());
        assertEquals(1, exported.size(),
                "the two placeholders must not appear, mapped or unmapped");
    }

    @Test
    void aCardWithTextButNoImageIsStillContent() {
        context.create().resource("/content/s/en/p/jcr:content/root/block", "filter", "cards");
        context.create().resource("/content/s/en/p/jcr:content/root/block/item",
                "model", "card", "text", "<p>Just words, no picture.</p>");
        final List<Map<String, Object>> exported =
                walk(context.resourceResolver().getResource("/content/s/en/p/jcr:content/root/block"));
        assertEquals(1, exported.size(), "a text-only card is content and must survive");
    }
```

- [ ] **Step 2: Run and confirm the first fails**

Expected: 3 components — the authored card plus the two placeholders "exported unmapped" with their raw `selectable`/`imagestyle`/`customstyles`. The second test should already pass; it is the guard that the predicate is not too greedy.

- [ ] **Step 3: Skip empty cards in the walk**

In `collectComponents`, after the `isSystemNode` check and before the cards-block interception:

```java
            if (isEmptyCard(child)) {
                LOG.debug("Skipping card with no image or text at {}", child.getPath());
                continue;
            }
```

Placement matters: it must come **before** the `isCardsBlock` branch so an empty card is never walked, and after `isSystemNode` so the existing skip order is unchanged.

- [ ] **Step 4: Run the tests and confirm all pass**

```bash
mvn test -pl core
```

Expected: the full suite green — 15 existing plus the new ones.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/edsMobileApp/core/services/impl/ComponentTreeWalker.java \
        core/src/test/java/com/edsMobileApp/core/services/impl/EmptyCardTest.java
git commit -m "Do not export a card with no image or text"
```

---

## Task 3: Verify against the live export

**Files:** none.

- [ ] **Step 1: Baseline before deploying**

Capture all sites first, so any difference is attributable:

```
/content/wknd-fly
/content/halliby/language-masters/en
/content/carvelo/language-masters/en
/content/secur-financial/language-masters/en
/content/citi-signal
/content/we-healthcare/language-masters/en
```

- [ ] **Step 2: Deploy and diff**

Expected on `wknd-fly` → `destinations`: a **`card`** component for India appears, alongside the three existing `screenList` lists, which must be unchanged:

```
list screenList ["mexico","poland","united-kingdom"]
list screenList ["france","jamaica","united-states"]
list screenList ["netherlands","japan","united-arab-emirates"]
card  title="India"                      <- new
```

- [ ] **Step 3: Check every other site diffs empty — and investigate if not**

Any other site that changes has the same latent bug: a three-card block containing an empty placeholder, currently dropped whole. That is the fix working, but confirm each one is content that *should* appear rather than a surprise. Note that India will render as a single full-width card, not as part of a row — the block has one real card, so there is no row to join.

- [ ] **Step 4: Tell the author about the placeholders**

The underlying content is still two empty cards in a block. If the intent was a fourth destinations row, the fix makes India visible but the row is still incomplete — `las-vegas`, `chicago` and `los-angeles` are exported screens that appear in no row. Authoring two of them into those placeholders turns the block into a proper screenList row, which is probably what was wanted.

---

## Open Questions

1. **Should a card with a link but no image or text count as empty?** This plan says yes — nothing renders, so nothing should export. If a link-only card is meant to be a tappable blank tile somewhere, the predicate needs a `link` term.
2. **Should an empty card be logged louder than `debug`?** It is authored content that silently does nothing. A `warn` naming the path would surface these to authors, at the cost of noise on sites that use placeholders deliberately.

---

## Self-Review

- **Spec coverage.** Reported symptom (India dropped) → Task 1. The second defect found while tracing (empty cards exported unmapped) → Task 2. Live confirmation → Task 3.
- **Placeholders.** Task 1 Step 1 leaves the test's `walk`/`meta` helpers to the implementer with an explicit fallback, because `ComponentTreeWalker`'s constructor needs a `ModelFactory` I have not wired under aem-mock — that is a stated escape hatch, not an unfinished step.
- **Type consistency.** `isEmptyCard(Resource)` has one signature, used in `cardItemsOf` and `collectComponents`. `isBlankValue(Object)` is used only by it. Property names match `card.json`'s `first: ["image", "fileReference", "imageRef"]`.
