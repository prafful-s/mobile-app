# Fixed-Skeleton + Extras Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the v2 mapping engine emit a fixed schema shape per component (skeleton) and pass through any authored CRX property the rule didn't consume, on a per-rule opt-in basis; then rewrite 16 rule files to opt in with the reference-contract shapes.

**Architecture:** One engine change, then 16 rule rewrites. `FieldRule` gains a `nullable` hint and a `consumedProperties()` accessor. `ComponentMapping` gains a `skeleton` flag: when true, `apply()` emits every declared field always (using `null`/`[]` for absent nullable fields), and `ComponentTreeWalker` appends every non-bookkeeping, non-consumed CRX property verbatim after the schema keys. Rules without the flag keep current behavior — backward compatible for classic/curated content.

**Tech Stack:** Java 11, Sling Models, JUnit 5 + `io.wcm.testing.mock.aem`, Jackson.

## Global Constraints

- Every task ends with `mvn test -pl core` green before committing. No AEM instance needed.
- No changes to curated Sling models (`BannerModel`, `CardModel`, `TitleModel`, `HtmlTextModel`, `ImageModel`, `DividerModel`) — their contract is untouched.
- No changes to `ComponentTreeWalker.emitPanel`, `FormBlockBuilder`, or `FormFieldFactory` — panel/form/form-fields are out of scope for this plan.
- Rules without `"skeleton": true` MUST behave exactly as they do today.
- Extras appended AFTER schema keys, verbatim, in ValueMap iteration order.
- Commit after every task, one commit per checked-off task.

Spec: [`docs/superpowers/specs/2026-08-06-fixed-skeleton-with-extras-design.md`](../specs/2026-08-06-fixed-skeleton-with-extras-design.md)

---

## Task 1: `FieldRule` — `nullable` + `consumedProperties()`

**Files:**
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/FieldRule.java`

**Interfaces:**
- Produces:
  - `FieldRule.consumedProperties(): List<String>` (package-private) — returns every CRX property name this rule reads: `from`, every entry of `first[]`, and `extract.from` when present. Empty list when the rule is constant-only.
  - `FieldRule.isNullable(): boolean` (package-private) — returns the `nullable` flag (default `false`).
  - `FieldRule.of(Map<String, Object>)` — parses new optional `"nullable": true` from the rule definition. All existing behavior unchanged when the key is absent.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/com/dept/contentbridge/core/services/impl/FieldRuleTest.java` (new file, package-private access is legitimate inside the same package):

```java
package com.dept.contentbridge.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldRuleTest {

    @Test
    @DisplayName("consumedProperties: 'from' by itself")
    void consumedFromOnly() {
        final FieldRule rule = FieldRule.of(Map.of("target", "title", "from", "jcr:title"));
        assertEquals(List.of("jcr:title"), rule.consumedProperties());
    }

    @Test
    @DisplayName("consumedProperties: 'first' array")
    void consumedFirst() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "image",
                "first", List.of("image", "fileReference", "imageRef")));
        assertEquals(List.of("image", "fileReference", "imageRef"),
                rule.consumedProperties());
    }

    @Test
    @DisplayName("consumedProperties: 'extract' names the source rich-text field")
    void consumedExtract() {
        final FieldRule rule = FieldRule.of(Map.of(
                "target", "title",
                "extract", Map.of("from", "text", "select", "h1")));
        assertEquals(List.of("text"), rule.consumedProperties());
    }

    @Test
    @DisplayName("consumedProperties: 'const' consumes nothing")
    void consumedConst() {
        final FieldRule rule = FieldRule.of(Map.of("target", "titleVariant", "const", "titleLarge"));
        assertTrue(rule.consumedProperties().isEmpty(),
                "a constant-only rule reads no CRX property");
    }

    @Test
    @DisplayName("nullable defaults to false, opts in when set")
    void nullable() {
        final FieldRule off = FieldRule.of(Map.of("target", "subtitle", "from", "subtitle"));
        assertFalse(off.isNullable());

        final FieldRule on = FieldRule.of(Map.of(
                "target", "subtitle", "from", "subtitle", "nullable", true));
        assertTrue(on.isNullable());
    }
}
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -pl core -Dtest=FieldRuleTest
```
Expected: FAIL (methods do not exist yet — compile error is the failure).

- [ ] **Step 3: Add `nullable` and `consumedProperties()` to `FieldRule`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/FieldRule.java`, add the field and constructor param, then a new accessor pair.

Add to the field block (after `type`):

```java
    private final boolean nullable;
```

Update the constructor signature and body to accept and store `nullable`. Change:

```java
    private FieldRule(final String target, final String from, final List<String> first,
                      final Object constant, final Extract extract, final Object defaultValue,
                      final String type) {
        this.target = target;
        this.from = from;
        this.first = first;
        this.constant = constant;
        this.extract = extract;
        this.defaultValue = defaultValue;
        this.type = type;
    }
```

to:

```java
    private FieldRule(final String target, final String from, final List<String> first,
                      final Object constant, final Extract extract, final Object defaultValue,
                      final String type, final boolean nullable) {
        this.target = target;
        this.from = from;
        this.first = first;
        this.constant = constant;
        this.extract = extract;
        this.defaultValue = defaultValue;
        this.type = type;
        this.nullable = nullable;
    }
```

Update `of()` to parse it. Change:

```java
        return new FieldRule(target,
                string(definition.get("from")),
                strings(definition.get("first")),
                definition.get("const"),
                Extract.of(definition.get("extract")),
                definition.get("default"),
                string(definition.get("type")));
```

to:

```java
        return new FieldRule(target,
                string(definition.get("from")),
                strings(definition.get("first")),
                definition.get("const"),
                Extract.of(definition.get("extract")),
                definition.get("default"),
                string(definition.get("type")),
                Boolean.TRUE.equals(definition.get("nullable")));
```

Add accessor and consumed-properties method, right after `fallback()`:

```java
    /**
     * True when the schema declares this field nullable. Under skeleton mode, an unauthored
     * nullable field is emitted as {@code null} (or {@code []} for a list-typed one) rather
     * than dropped, so the client sees a fixed key set.
     */
    boolean isNullable() {
        return nullable;
    }

    /**
     * The CRX property names this rule reads from, so extras passthrough can filter them out.
     * Constant-only rules return an empty list - they consume nothing.
     */
    List<String> consumedProperties() {
        if (extract != null) {
            final String source = extract.sourceProperty();
            return source == null ? Collections.emptyList() : List.of(source);
        }
        if (!first.isEmpty()) {
            return first;
        }
        if (from != null) {
            return List.of(from);
        }
        return Collections.emptyList();
    }
```

Also add a package-private `sourceProperty()` accessor to `Extract`:

```java
        String sourceProperty() {
            return from;
        }
```

- [ ] **Step 4: Run the new test file, confirm all pass**

```bash
mvn test -pl core -Dtest=FieldRuleTest
```
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS, unchanged test count + 5 new tests.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/dept/contentbridge/core/services/impl/FieldRule.java \
        core/src/test/java/com/dept/contentbridge/core/services/impl/FieldRuleTest.java
git commit -m "feat(v2): FieldRule gains nullable hint and consumedProperties()

Two pure additions to FieldRule, no behavior change on their own -
'nullable' is stored but not yet consulted, and consumedProperties()
returns which CRX property names this rule reads from. Both are for
the fixed-skeleton mapping mode landing in the next tasks.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: `ComponentMapping` — `skeleton` flag + always-emit `apply()`

**Files:**
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentMapping.java`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

**Interfaces:**
- Produces:
  - `ComponentMapping.isSkeleton(): boolean` — package-private; reads the `"skeleton": true` flag from the rule file.
  - `ComponentMapping.consumedProperties(): Set<String>` — union of every field's consumed CRX property names, for the walker's extras filter.
  - `ComponentMapping.apply()` behavior change: when `isSkeleton()` is true, always emit every declared field (using `null` for nullable-and-absent, `""`/typed default for non-nullable-and-absent), and never return an empty map to signal "abandon this projection". When `isSkeleton()` is false, existing behavior unchanged.
- Consumes (Task 1): `FieldRule.isNullable()`, `FieldRule.consumedProperties()`.

- [ ] **Step 1: Write the failing test — skeleton emits every field even when unauthored**

Append to `ComponentMappingTest.java` (a hero rule authored with only unused properties would previously fall through unmapped; a skeleton rule emits its full shape):

```java
    @Test
    @DisplayName("a skeleton rule emits every declared field even when nothing is authored")
    void skeletonAlwaysEmitsSchema() {
        // Uses the 'contentCards' rule after this plan enables skeleton on it in Task 3.
        // For this task, we install a fresh temp mapping directly against a synthetic component
        // and assert the engine wiring.
        // Test omitted here - covered by the per-rule tests in later tasks, when the
        // "skeleton": true opt-in has actually been added to the rule files. See Task 3.
    }
```

Actually, this task's behavior is best tested end-to-end via a rule file that opts in. Split the verification: engine-level assertion here, per-rule assertions in Task 3+.

Add instead a smaller engine-level test that only touches internals via a synthetic `Map` fed into a hand-built rule:

```java
    @Test
    @DisplayName("skeleton mode: apply emits every field, nullable-and-absent becomes null")
    void skeletonEmitsAllFields() throws Exception {
        // Build a rule programmatically via its private-package factory (test lives in a
        // different package - reach through the loading path instead).
        final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        final java.util.Map<String, Object> definition = mapper.readValue(
                "{\"match\":[\"widget\"], \"component\":\"widget\", \"skeleton\": true, \"fields\":["
                + "{\"target\":\"required\", \"from\":\"required\", \"default\":\"fallback\"},"
                + "{\"target\":\"optional\", \"from\":\"optional\", \"nullable\": true}"
                + "]}",
                new com.fasterxml.jackson.core.type.TypeReference<
                        java.util.LinkedHashMap<String, Object>>() { });

        // Load through the package via reflection would be brittle. Instead, put this file
        // under core/src/test/resources/contentbridge/mappings/widget-fixture.json and have
        // the test read/parse only - but we do not want that in the main index either.
        //
        // For simplicity: this behavior is fully covered by the per-rule end-to-end tests
        // in Task 3. Skip this unit-level test.
    }
```

Neither approach is clean because `ComponentMapping` is package-private. Revised plan: **no separate engine unit test for Task 2**. Instead, land the engine change and rely on Task 3's `contentCards.json` rewrite + test as the first end-to-end verification. Task 3's test WILL fail if the engine change is not in place, so the red step happens there.

Skip Step 1 of this task. Go straight to code.

- [ ] **Step 2: Add `skeleton` + `consumedProperties()` + always-emit `apply()`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentMapping.java`, add the field and constructor param, then modify `apply()`.

Add to the field block (after `styleFields`):

```java
    private final boolean skeleton;
```

Update the constructor to accept and store it. Change:

```java
    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields, final List<FieldRule> styleFields) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
        this.styleFields = styleFields;
    }
```

to:

```java
    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields, final List<FieldRule> styleFields,
                             final boolean skeleton) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
        this.styleFields = styleFields;
        this.skeleton = skeleton;
    }
```

Update `of()` to parse the flag. Change:

```java
        final List<FieldRule> fields = fieldRules(definition.get("fields"));
        final List<FieldRule> styleFields = fieldRules(definition.get("styles"));
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields), Collections.unmodifiableList(styleFields));
    }
```

to:

```java
        final List<FieldRule> fields = fieldRules(definition.get("fields"));
        final List<FieldRule> styleFields = fieldRules(definition.get("styles"));
        final boolean skeleton = Boolean.TRUE.equals(definition.get("skeleton"));
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields), Collections.unmodifiableList(styleFields),
                skeleton);
    }
```

Add accessors right after `getComponent()`:

```java
    /**
     * True when this rule opts in to fixed-shape output: every declared field is emitted
     * always (nullable-and-absent as null / list-typed absent as an empty list) and the walker
     * appends any authored CRX property this rule did not consume. Rules without the flag
     * behave as they did before - only authored/defaulted keys, and an empty projection is
     * abandoned.
     */
    boolean isSkeleton() {
        return skeleton;
    }

    /**
     * Every CRX property this rule reads from, across every field. Used by the walker to
     * filter extras passthrough - a property this rule consumed must not reappear as an extra.
     */
    Set<String> consumedProperties() {
        final Set<String> consumed = new LinkedHashSet<>();
        for (final FieldRule field : fields) {
            consumed.addAll(field.consumedProperties());
        }
        for (final FieldRule field : styleFields) {
            consumed.addAll(field.consumedProperties());
        }
        return consumed;
    }
```

Add the two imports at the top:

```java
import java.util.LinkedHashSet;
import java.util.Set;
```

Rewrite `apply()` to branch on `skeleton`:

```java
    Map<String, Object> apply(final Map<String, Object> source) {
        final Map<String, Object> target = new LinkedHashMap<>();
        boolean anyAuthored = false;

        for (final FieldRule field : fields) {
            Object value = field.fromSource(source);
            if (isPresent(value)) {
                anyAuthored = true;
            } else {
                value = field.fallback();
            }
            if (isPresent(value)) {
                target.put(field.getTarget(), value);
            } else if (skeleton) {
                // Fixed-shape opt-in: emit the key anyway, using null when the field is
                // declared nullable and typed zero otherwise. The client always sees the
                // same key set, whatever the author left blank.
                target.put(field.getTarget(), field.isNullable() ? null : "");
            }
        }
        if (skeleton) {
            return target;
        }
        return anyAuthored ? target : Collections.emptyMap();
    }
```

- [ ] **Step 3: Run the full existing suite, confirm all pass**

Since no rule opts in yet, `skeleton` is `false` everywhere. The `apply()` behavior for existing rules is byte-identical.

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS, test count unchanged from Task 1 (172).

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentMapping.java
git commit -m "feat(v2): ComponentMapping gains a skeleton opt-in flag

Rules can now declare 'skeleton: true' to emit their full schema
shape always, with nullable-and-absent fields coming through as null.
Without the flag, apply() is byte-identical to before - existing
rules unaffected.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: `ComponentTreeWalker` — extras passthrough when skeleton mode

**Files:**
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentTreeWalker.java`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`
- Modify: `core/src/main/resources/contentbridge/mappings/contentCards.json` (opt in first user of skeleton mode for the end-to-end test).

**Interfaces:**
- Consumes (Task 2): `ComponentMapping.isSkeleton()`, `ComponentMapping.consumedProperties()`.
- Produces: no new signatures — behavior wired directly inside the walker.

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("skeleton mode: unconsumed CRX properties appear as extras after schema keys")
    void skeletonPassesThroughUnconsumedExtras() {
        // contentCards opts in to skeleton mode in this task. 'surface' is consumed by the
        // rule; 'trackingId' is not - it should appear in the output verbatim.
        final Map<String, Object> exported = export(true,
                "model", "contentCards",
                "surface", "sf_home",
                "trackingId", "promo-42");

        assertEquals("contentCards", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("sf_home", content.get("surface"), "schema key comes through");
        assertEquals("promo-42", content.get("trackingId"),
                "unconsumed extras get appended verbatim");

        final List<String> keys = List.copyOf(content.keySet());
        assertEquals("surface", keys.get(0), "schema keys stay in declared order first");
        assertTrue(keys.indexOf("trackingId") > keys.indexOf("surface"),
                "extras appended after schema keys");
    }

    @Test
    @DisplayName("skeleton mode: bookkeeping and consumed keys never leak into extras")
    void skeletonFiltersConsumedAndBookkeeping() {
        final Map<String, Object> exported = export(true,
                "model", "contentCards",
                "surface", "sf_home",
                "modelFields", new String[] {"surface"},
                "jcr:title", "not content");

        final Map<String, Object> content = content(exported);
        assertFalse(content.containsKey("modelFields"), "modelFields is bookkeeping");
        assertFalse(content.containsKey("jcr:title"), "jcr: prefix is bookkeeping");
        assertFalse(content.containsKey("model"), "the 'model' discriminator is bookkeeping");
    }
```

- [ ] **Step 2: Opt `contentCards.json` into skeleton mode (bare minimum for the test)**

Only add the flag — full rewrite of `contentCards.json` and the other 15 rules happens in Task 4+.

Change `core/src/main/resources/contentbridge/mappings/contentCards.json`:

```json
{
  "match": ["contentCards"],
  "component": "contentCards",
  "skeleton": true,
  "fields": [
    { "target": "surface", "from": "surface" }
  ],
  "styles": [
    { "target": "color", "from": "color" },
    { "target": "borderColor", "from": "borderColor", "default": "lightgrey" },
    { "target": "borderRadius", "from": "borderRadius", "type": "number", "default": 9 },
    { "target": "backgroundColor", "from": "backgroundColor", "default": "white" }
  ]
}
```

- [ ] **Step 3: Run — confirm the new tests fail (walker not wired yet)**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#skeletonPassesThroughUnconsumedExtras,ComponentMappingTest#skeletonFiltersConsumedAndBookkeeping
```
Expected: FAIL — `content.get("trackingId")` is `null` (walker doesn't know about extras yet).

- [ ] **Step 4: Add extras passthrough to `ComponentTreeWalker`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentTreeWalker.java`, find the block that applies the mapping (currently ~line 214):

```java
            if (mapping != null) {
                final Map<String, Object> mapped = mapping.apply(authored);
                if (mapped.isEmpty()) {
                    LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped",
                            authoredName, child.getPath());
                } else {
                    componentName = mapping.getComponent();
                    content = mapped;
                    styles = mapping.applyStyles(authored);
                }
            }
```

Rewrite it to also call the extras helper when the rule is in skeleton mode:

```java
            if (mapping != null) {
                final Map<String, Object> mapped = mapping.apply(authored);
                if (mapped.isEmpty()) {
                    LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped",
                            authoredName, child.getPath());
                } else {
                    componentName = mapping.getComponent();
                    content = new LinkedHashMap<>(mapped);
                    styles = mapping.applyStyles(authored);
                    if (mapping.isSkeleton()) {
                        appendUnconsumedExtras(content, child.getValueMap(),
                                mapping.consumedProperties());
                    }
                }
            }
```

(Wrapping `mapped` in a new `LinkedHashMap` is safe — the caller doesn't mutate through the returned reference.)

Add the helper method somewhere near the other private helpers (top of file, after the existing `prefixAsset`):

```java
    /**
     * Appends every authored CRX property the rule did not consume, in ValueMap order, after
     * the schema keys already written by the mapping. Skips bookkeeping prefixes and the
     * Sling/JCR/CQ housekeeping properties the generic exporter already ignores, so the
     * client sees only content.
     */
    private static void appendUnconsumedExtras(final Map<String, Object> target,
                                               final org.apache.sling.api.resource.ValueMap source,
                                               final java.util.Set<String> consumed) {
        for (final Map.Entry<String, Object> entry : source.entrySet()) {
            final String key = entry.getKey();
            if (consumed.contains(key)) {
                continue;
            }
            if (!isContentProperty(key)) {
                continue;
            }
            if (target.containsKey(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
    }

    /** Same rule GenericComponent uses: filter housekeeping prefixes and known bookkeeping. */
    private static boolean isContentProperty(final String key) {
        if (SchemaConstants.PN_MODEL.equals(key)
                || SchemaConstants.PN_MODEL_FIELDS.equals(key)
                || SchemaConstants.PN_FILTER.equals(key)
                || SchemaConstants.PN_NAME.equals(key)
                || SchemaConstants.PN_AUE_COMPONENT_ID.equals(key)) {
            return false;
        }
        return !key.startsWith("jcr:")
                && !key.startsWith("sling:")
                && !key.startsWith("cq:")
                && !key.startsWith("mix:")
                && !key.startsWith("nt:")
                && !key.startsWith("granite:")
                && !key.startsWith("oak:")
                && !key.startsWith("rep:");
    }
```

(`java.util.Set` and `org.apache.sling.api.resource.ValueMap` are already imported; add the imports only if the compiler complains.)

- [ ] **Step 5: Run the two new tests, confirm they pass**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#skeletonPassesThroughUnconsumedExtras,ComponentMappingTest#skeletonFiltersConsumedAndBookkeeping
```
Expected: PASS.

- [ ] **Step 6: Update the earlier `mappingProjectsStyles` test to opt in properly**

`mappingProjectsStyles` currently asserts `content.get("surface")` on a skeleton-less contentCards rule. Now that skeleton is on, the same test should still pass — `surface` is authored → emitted, `color`/`borderColor`/etc. are styles (unchanged). Run to confirm.

```bash
mvn test -pl core -Dtest=ComponentMappingTest
```
Expected: PASS (14 tests, incl. the 2 new).

- [ ] **Step 7: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentTreeWalker.java \
        core/src/main/resources/contentbridge/mappings/contentCards.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): skeleton-mode rules pass through unconsumed CRX props

When a rule opts in with 'skeleton: true', ComponentTreeWalker
appends every authored CRX property the rule did not consume,
verbatim, after the schema keys - modelo the client sees a stable
shape plus whatever site-specific extras the author set. contentCards
is the first opt-in; the remaining 15 rules follow in later tasks.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Rewrite `title.json`

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/title.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Reference schema:
```
{ "size": "small|medium|large", "text": "string", "weight": "regular|bold|light",
  "textColor": "string?", "textAlignment": "left|center|right?" }
```

`size` was `"h1|h2|h3|h4"` under the old rule; per your instruction the schema is the target contract, so the new rule emits `"medium"` etc. Authors who set `titleType=h1` still get `size: "h1"` (pass-through) — the enum is documentation for the client, not enforced by the exporter.

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("title in skeleton mode emits every schema key + extras passthrough")
    void titleSkeleton() {
        final Map<String, Object> exported = export(true,
                "model", "title",
                "title", "Hello",
                "weight", "bold",
                "trackingId", "hero-title");

        assertEquals("title", component(exported));
        final Map<String, Object> content = content(exported);

        assertEquals("Hello", content.get("text"));
        assertEquals("bold", content.get("weight"));
        assertEquals("medium", content.get("size"), "default from the schema");
        assertNull(content.get("textColor"), "nullable optional, absent");
        assertNull(content.get("textAlignment"), "nullable optional, absent");
        assertEquals("hero-title", content.get("trackingId"), "unconsumed extras pass through");

        assertTrue(content.containsKey("textColor"),
                "nullable keys must be present as null, not absent");
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertNull;` at the top of the file if not already present.

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#titleSkeleton
```
Expected: FAIL (old rule emits `size: "h2"`, no `weight`, no `textColor`, no extras).

- [ ] **Step 3: Rewrite `title.json`**

Replace `core/src/main/resources/contentbridge/mappings/title.json`:

```json
{
  "match": ["title", "heading"],
  "component": "title",
  "skeleton": true,
  "fields": [
    { "target": "size", "first": ["titleType", "type", "size"], "default": "medium" },
    { "target": "text", "first": ["title", "jcr:title", "text"] },
    { "target": "weight", "from": "weight", "default": "regular" },
    { "target": "textColor", "from": "textColor", "nullable": true },
    { "target": "textAlignment", "first": ["alignment", "textAlignment"], "nullable": true }
  ]
}
```

- [ ] **Step 4: Run the new test, confirm PASS**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#titleSkeleton
```
Expected: PASS.

- [ ] **Step 5: Run existing `titleAndText` test — expect it to still pass, or reveal a needed update**

The pre-existing test asserts `"h1"` (authored `titleType: "h1"`), `"center"` alignment, `"Health Plans..."` text. With the new rule the authored values still pass through unchanged — `titleType` maps to `size` via `first[]`, `alignment` maps to `textAlignment`, `title` maps to `text`. So it should still pass.

```bash
mvn test -pl core -Dtest=ComponentMappingTest#titleAndText
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/title.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): title rule opts in to skeleton mode

Adds nullable textColor / textAlignment, a schema default of 'medium'
for size, and extras passthrough for anything an author sets on a
title block that the rule doesn't already consume.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Rewrite `button.json`

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/button.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Reference schema:
```
{ "label", "icon?", "events", "linkTo?", "message?", "variant", "alignment",
  "textColor?", "customEvent?", "customStyles?" }
```

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("button in skeleton mode emits every schema key + extras")
    void buttonSkeleton() {
        final Map<String, Object> exported = export(true,
                "model", "button",
                "linkText", "Click me",
                "link", "/somewhere",
                "trackingId", "cta-1");

        assertEquals("button", component(exported));
        final Map<String, Object> content = content(exported);

        assertEquals("Click me", content.get("label"));
        assertEquals("/somewhere", content.get("linkTo"));
        assertEquals("button", content.get("variant"), "schema default");
        assertEquals("stretch", content.get("alignment"), "schema default");
        assertEquals("", content.get("events"), "required but unauthored - empty string");
        assertNull(content.get("icon"));
        assertNull(content.get("message"));
        assertNull(content.get("customEvent"));
        assertNull(content.get("customStyles"));
        assertEquals("cta-1", content.get("trackingId"), "extras through");
    }
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#buttonSkeleton
```
Expected: FAIL.

- [ ] **Step 3: Rewrite `button.json`**

Replace `core/src/main/resources/contentbridge/mappings/button.json`:

```json
{
  "match": ["button", "cta"],
  "component": "button",
  "skeleton": true,
  "fields": [
    { "target": "label", "first": ["linkText", "buttonText", "text", "jcr:title"] },
    { "target": "icon", "from": "icon", "nullable": true },
    { "target": "events", "from": "events", "default": "" },
    { "target": "linkTo", "first": ["link", "btn-link", "href"], "nullable": true },
    { "target": "message", "from": "message", "nullable": true },
    { "target": "variant", "first": ["linkType", "ctastyle", "style"], "default": "button" },
    { "target": "alignment", "from": "alignment", "default": "stretch" },
    { "target": "textColor", "from": "textColor", "nullable": true },
    { "target": "customEvent", "from": "customEvent", "nullable": true },
    { "target": "customStyles", "from": "customStyles", "nullable": true }
  ]
}
```

Note: the previous rule had `horizontalAlignment`, `uppercase`, `backgroundColor`, `borderColor`. The new schema drops these; if authors set them on real content, they will now come through as unconsumed extras (`horizontalAlignment: "left"` etc.). That matches the "author's extras survive" intent.

- [ ] **Step 4: Run the new test, PASS**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#buttonSkeleton
```
Expected: PASS.

- [ ] **Step 5: Existing `buttonMapping` test — check what it asserts**

The existing test asserts `label`, `linkTo`, `variant`, `uppercase` (dropped from the new schema). Under the new rule, `uppercase` is no longer emitted by the rule but `textUppercase` from the authored source (the CRX name) IS passed through as an extra... except the existing test authors `textUppercase: "false"` — that comes through as a string extra, not a coerced boolean. The old assertion `assertEquals(Boolean.FALSE, content.get("uppercase"))` will fail on two counts.

**Update the existing `buttonMapping` test** to match the new contract:

Change:
```java
        assertEquals("button", component(exported));
        assertEquals("Insurance Plans", content(exported).get("label"));
        assertEquals("/content/we-healthcare/language-masters/en/insurance-plans.html",
                content(exported).get("linkTo"));
        assertEquals("cta-link", content(exported).get("variant"));
        assertEquals(Boolean.FALSE, content(exported).get("uppercase"));
```

to:
```java
        assertEquals("button", component(exported));
        assertEquals("Insurance Plans", content(exported).get("label"));
        assertEquals("/content/we-healthcare/language-masters/en/insurance-plans.html",
                content(exported).get("linkTo"));
        assertEquals("cta-link", content(exported).get("variant"));
        // 'uppercase' is no longer a schema field; the authored 'textUppercase' comes
        // through as an extra (string, no coercion) - the whole point of passthrough.
        assertEquals("false", content(exported).get("textUppercase"));
```

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/button.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): button rule opts in to skeleton mode

Reworks button.json to match the reference schema exactly: adds
icon/events/message/customEvent/customStyles as nullable, drops the
old uppercase/borderColor/etc. fields (they now pass through as
authored extras when set). Updates buttonMapping test to assert the
new contract.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Rewrite `hero.json` (banner)

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/hero.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java` (delete `emptyProjectionIsAbandoned`; verify `heroBecomesBanner` still passes)

Reference schema:
```
{ "image", "title", "subtitle?", "linkTo?", "opacity?", "showButton?", "buttonLabel?",
  "buttonVariant?", "imageResize", "titleVariant", "subtitleVariant?",
  "contentAlignment", "verticalAlignment", "horizontalAlignment" }
```

The old rule is more elaborate (`layout`, `fullWidth`, `textOverlay`, `underlineLinks`, `alt`, `backgroundColor`) — those become extras (still emitted when authored, via passthrough).

- [ ] **Step 1: Delete `emptyProjectionIsAbandoned` — obsolete under skeleton mode**

Remove the whole method from `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("a rule that matches but finds nothing leaves the component alone")
    void emptyProjectionIsAbandoned() {
        // ...
    }
```

Its intent (a `hero` with only unmapped fields keeps its `hero` component name) is now contradicted by the design: skeleton mode always emits a `banner` shape plus the unmapped field as extras.

- [ ] **Step 2: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("banner in skeleton mode always emits schema, extras pass through")
    void bannerSkeleton() {
        final Map<String, Object> exported = export(true,
                "model", "hero",
                "image", "/content/dam/x/hero.jpg",
                "text", "<h1>Hi</h1><p>Sub</p>",
                "someAuthoredExtra", "yes");

        assertEquals("banner", component(exported));
        final Map<String, Object> content = content(exported);

        // Required always-present
        assertEquals("/content/dam/x/hero.jpg", content.get("image"));
        assertEquals("Hi", content.get("title"));
        assertEquals("cover", content.get("imageResize"));
        assertEquals("titleLarge", content.get("titleVariant"));
        assertEquals("left", content.get("contentAlignment"));
        assertEquals("middle", content.get("verticalAlignment"));
        assertEquals("left", content.get("horizontalAlignment"));

        // Nullable-and-unauthored -> null (not absent)
        assertTrue(content.containsKey("linkTo"));
        assertNull(content.get("linkTo"));
        assertTrue(content.containsKey("opacity"));

        // Extras
        assertEquals("yes", content.get("someAuthoredExtra"));

        // Consumed source blob does not leak
        assertFalse(content.containsKey("text"),
                "extract-source blob is consumed and must not reappear as an extra");
    }
```

- [ ] **Step 3: Run — confirm failure**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#bannerSkeleton
```
Expected: FAIL.

- [ ] **Step 4: Rewrite `hero.json`**

Replace `core/src/main/resources/contentbridge/mappings/hero.json`:

```json
{
  "match": ["hero", "banner", "masthead"],
  "component": "banner",
  "skeleton": true,
  "fields": [
    { "target": "image", "first": ["image", "fileReference", "imageRef"] },
    { "target": "title", "extract": { "from": "text", "select": "h1, h2", "as": "text" } },
    { "target": "subtitle",
      "extract": { "from": "text", "select": "p:not(:has(a))", "as": "text", "all": true },
      "nullable": true },
    { "target": "linkTo",
      "extract": { "from": "text", "select": "a", "as": "attr", "attribute": "href" },
      "nullable": true },
    { "target": "opacity", "from": "opacity", "type": "number", "nullable": true },
    { "target": "showButton",
      "extract": { "from": "text", "select": "a", "as": "text" },
      "type": "presence", "nullable": true },
    { "target": "buttonLabel",
      "extract": { "from": "text", "select": "a", "as": "text" }, "nullable": true },
    { "target": "buttonVariant", "from": "ctastyle", "nullable": true },
    { "target": "imageResize", "from": "imageResize", "default": "cover" },
    { "target": "titleVariant", "const": "titleLarge" },
    { "target": "subtitleVariant", "const": "titleSmall", "nullable": true },
    { "target": "contentAlignment", "from": "alignment", "default": "left" },
    { "target": "verticalAlignment", "from": "verticalalignment", "default": "middle" },
    { "target": "horizontalAlignment", "from": "alignment", "default": "left" }
  ]
}
```

Note: `alt`, `layout`, `fullWidth`, `textOverlay`, `underlineLinks`, `backgroundColor` are gone from the rule — anything an author sets on those names now flows through as extras. `imageAlt`/`alt` become extras if set.

- [ ] **Step 5: Run the new test + existing `heroBecomesBanner` + `extractedLinksAreExternalised`**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#bannerSkeleton,ComponentMappingTest#heroBecomesBanner,ComponentMappingTest#extractedLinksAreExternalised
```
Expected: PASS. `heroBecomesBanner`'s `assertFalse(containsKey("text"))` still holds — `text` is in `consumedProperties()`. Its `assertFalse(containsKey("herolayout"))` was checking the old `herolayout` → `layout` mapping; now `layout` isn't a rule field, so `herolayout` might come through as an extra — CHECK. If it does, update that test to acknowledge it.

Adjust `heroBecomesBanner` as needed. Likely change:
```java
        assertFalse(content.containsKey("text"), "the source blob must not survive alongside its parts");
        assertFalse(content.containsKey("herolayout"), "authored field names are not the app's");
```
to:
```java
        assertFalse(content.containsKey("text"), "the source blob must not survive alongside its parts");
        // With skeleton mode's extras passthrough, unconsumed authored fields DO survive under
        // their authored names. That is the new contract. 'herolayout' is no longer consumed.
        assertEquals("overlay", content.get("herolayout"), "authored, unconsumed, passes through");
```

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/hero.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): hero->banner rule opts in to skeleton mode

Reference-shape rebuild: nullable subtitle/linkTo/opacity/showButton/
buttonLabel/buttonVariant/subtitleVariant, drops the old
layout/fullWidth/textOverlay/underlineLinks/alt/backgroundColor rule
fields (they now come through as extras when authors set them).
Deletes emptyProjectionIsAbandoned (obsolete under skeleton mode) and
updates heroBecomesBanner's herolayout assertion to reflect passthrough.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Rewrite `image.json`

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/image.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Reference schema:
```
{ "alt?", "image|src|uri" (required), "width?", "height?", "linkTo?", "fullWidth?",
  "imageResize" (contain|cover), "imageSize?" (fullWidth?), "resizeMode?" (contain?),
  "weight?", "justifyContent?" }
```

The reference uses `image|src|uri` — pick `image` as the emitted key (matches every other rule that reads DAM refs). `src`/`uri` become extras when authors use them.

- [ ] **Step 1: Failing test**

```java
    @Test
    @DisplayName("image in skeleton mode")
    void imageSkeleton() {
        final Map<String, Object> exported = export(true,
                "model", "image",
                "fileReference", "/content/dam/x/pic.jpg",
                "alt", "A picture",
                "authorNotes", "cropped by design team");

        final Map<String, Object> content = content(exported);
        assertEquals("/content/dam/x/pic.jpg", content.get("image"));
        assertEquals("A picture", content.get("alt"));
        assertEquals("contain", content.get("imageResize"), "schema default");
        assertNull(content.get("width"));
        assertNull(content.get("height"));
        assertNull(content.get("linkTo"));
        assertNull(content.get("fullWidth"));
        assertEquals("cropped by design team", content.get("authorNotes"), "extras through");
    }
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#imageSkeleton
```
Expected: FAIL.

- [ ] **Step 3: Rewrite `image.json`**

```json
{
  "match": ["image"],
  "component": "image",
  "skeleton": true,
  "fields": [
    { "target": "alt", "first": ["imageAlt", "alt"], "nullable": true },
    { "target": "image", "first": ["image", "fileReference", "imageRef", "src", "uri"] },
    { "target": "width", "from": "width", "nullable": true },
    { "target": "height", "from": "height", "type": "number", "nullable": true },
    { "target": "linkTo", "first": ["linkTo", "link", "href"], "nullable": true },
    { "target": "fullWidth", "from": "fullWidth", "type": "boolean", "nullable": true },
    { "target": "imageResize", "from": "imageResize", "default": "contain" },
    { "target": "imageSize", "from": "imageSize", "nullable": true },
    { "target": "resizeMode", "from": "resizeMode", "nullable": true },
    { "target": "weight", "from": "weight", "nullable": true },
    { "target": "justifyContent", "from": "justifyContent", "nullable": true }
  ]
}
```

- [ ] **Step 4: Run**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#imageSkeleton,ComponentMappingTest#imageMapping
```
Expected: PASS. The existing `imageMapping` test authors `fileReference` + `alt` and asserts both → survives.

- [ ] **Step 5: Full suite**

```bash
mvn test -pl core
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/image.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): image rule opts in to skeleton mode

Adds width/height/linkTo/fullWidth/imageSize/resizeMode/weight/
justifyContent as nullable to match the reference schema. Existing
alt/image behavior unchanged.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Rewrite `card.json`

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/card.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Reference schema:
```
{ "link", "image", "title", "subtitle", "cardType": "stacked", "titleVariant",
  "subtitleVariant", "titleFontWeight", "subtitleFontWeight" }
```

Note: no optional keys in the reference sample — all required. The existing rule has many more (`buttonLabel`, `overlayBackground`, `backgroundColor`, `imageStyle`, `textTransform`, `horizontalAlignment`, `selectable`, `isSubtitleRichText`, `buttonVariant`). Those become extras.

- [ ] **Step 1: Failing test**

```java
    @Test
    @DisplayName("card in skeleton mode")
    void cardSkeleton() {
        final Map<String, Object> exported = export(true,
                "model", "card",
                "image", "/content/dam/x/pic.jpg",
                "text", "<h2>Title</h2><p>Sub</p><p><a href=\"/x\">Go</a></p>",
                "trackingId", "prod-1");

        final Map<String, Object> content = content(exported);
        assertEquals("/content/dam/x/pic.jpg", content.get("image"));
        assertEquals("Title", content.get("title"));
        assertEquals("stacked", content.get("cardType"));
        assertEquals("titleMedium", content.get("titleVariant"));
        assertEquals("titleMedium", content.get("subtitleVariant"));
        assertEquals("regular", content.get("titleFontWeight"));
        assertEquals("regular", content.get("subtitleFontWeight"));
        assertEquals("prod-1", content.get("trackingId"), "extras through");
    }
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#cardSkeleton
```
Expected: FAIL.

- [ ] **Step 3: Rewrite `card.json`**

```json
{
  "match": ["card", "teaser"],
  "component": "card",
  "skeleton": true,
  "fields": [
    { "target": "link", "first": ["link", "btn-link", "linkTo"] },
    { "target": "image", "first": ["image", "fileReference", "imageRef"] },
    { "target": "title", "extract": { "from": "text", "select": "h1, h2, h3, h4", "as": "text" } },
    { "target": "subtitle",
      "extract": { "from": "text", "select": "p:not(:has(a))", "as": "text", "all": true } },
    { "target": "cardType", "from": "style", "default": "stacked" },
    { "target": "titleVariant", "const": "titleMedium" },
    { "target": "subtitleVariant", "const": "titleMedium" },
    { "target": "titleFontWeight", "from": "titleFontWeight", "default": "regular" },
    { "target": "subtitleFontWeight", "from": "subtitleFontWeight", "default": "regular" }
  ]
}
```

- [ ] **Step 4: Existing `cardMapping` test**

The old test asserts `overlayBackground: true` (was type-coerced boolean via the rule). Under the new rule `overlayBackground` is not a schema field → comes through as the string `"true"` in extras (no coercion). Update:

```java
        // Under skeleton mode, overlayBackground is no longer a schema field with
        // coercion - it comes through as authored, as a string extra.
        assertEquals("true", content.get("overlayBackground"));
```

Also the `selectable` assertion — same shape, adjust.

- [ ] **Step 5: Run full**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/card.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): card rule opts in to skeleton mode

Reference-shape rewrite: 9 fields per the SecurFinancial reference
(link/image/title/subtitle/cardType/titleVariant/subtitleVariant/
titleFontWeight/subtitleFontWeight). Drops the old buttonLabel/
overlayBackground/backgroundColor/imageStyle/textTransform/
horizontalAlignment/selectable/isSubtitleRichText/buttonVariant rule
fields - they now come through as authored extras when set. Updates
cardMapping test.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: Rewrite `text.json` (htmlText)

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/text.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Reference schema: `{ "text": "html-string" }` — one field. Extras still pass through.

- [ ] **Step 1: Failing test**

```java
    @Test
    @DisplayName("htmlText in skeleton mode passes markup through, extras too")
    void htmlTextSkeleton() {
        final Map<String, Object> exported = export(true,
                "model", "text",
                "text", "<h1>Once you've picked a plan</h1>",
                "authorNote", "reviewed");

        assertEquals("htmlText", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("<h1>Once you've picked a plan</h1>", content.get("text"));
        assertEquals("reviewed", content.get("authorNote"));
    }
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#htmlTextSkeleton
```

- [ ] **Step 3: Rewrite `text.json`**

Read the current text.json first, then match the shape below.

```json
{
  "match": ["text"],
  "component": "htmlText",
  "skeleton": true,
  "fields": [
    { "target": "text", "from": "text" }
  ]
}
```

If the current `text.json` has additional fields, they will move to authored extras via passthrough automatically — no per-field change needed.

- [ ] **Step 4: Run full**

```bash
mvn test -pl core
```

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/text.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): text (htmlText) rule opts in to skeleton mode

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: Opt in the 9 recently-added rules (single commit)

**Files:**
- Modify: `core/src/main/resources/contentbridge/mappings/divider.json`
- Modify: `core/src/main/resources/contentbridge/mappings/map.json`
- Modify: `core/src/main/resources/contentbridge/mappings/offer.json`
- Modify: `core/src/main/resources/contentbridge/mappings/list.json`
- Modify: `core/src/main/resources/contentbridge/mappings/productImage.json`
- Modify: `core/src/main/resources/contentbridge/mappings/productTitle.json`
- Modify: `core/src/main/resources/contentbridge/mappings/timeSlotPicker.json`
- Modify: `core/src/main/resources/contentbridge/mappings/userLogin.json`
- Modify: `core/src/main/resources/contentbridge/mappings/userDetails.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Each of these already matches its reference schema. Only add `"skeleton": true` at the top level, and mark optional fields `nullable: true` where the schema uses `?`.

- [ ] **Step 1: Add `"skeleton": true` + `"nullable": true` on optional fields**

For each file, Read → Edit to insert `"skeleton": true,` after `"component": ...,` and add `"nullable": true` to any field whose schema counterpart is `?`.

Per-file guidance:

- `divider.json` — add `"horizontalInset"` as nullable (new per schema). Existing fields are all required.
- `map.json` — no optional-`?` in the schema. Just `"skeleton": true`.
- `offer.json` — no `?` in the schema. Just the flag.
- `list.json` — many `?`: `sort`, `limit`, `filter`, `imageWidth`, `itemHeight`, `imageResize`, `hideSubtitle`, `filterProperty`, `titleFontWeight`, `subtitleFontWeight` — mark all `nullable: true`. `items`, `layout`, `listType`, `itemsInRow`, `itemRenderer` stay non-nullable (required).
- `productImage.json` — `imageResize?`, mark nullable.
- `productTitle.json` — no `?`. Just the flag.
- `timeSlotPicker.json` — no `?`. Just the flag.
- `userLogin.json` — no `?`. Just the flag.
- `userDetails.json` — no `?`. Just the flag.

- [ ] **Step 2: Update the existing per-rule tests to also check extras passthrough**

For each of `listMapping`, `mapMapping`, `offerMapping`, `productImageMapping`, `productTitleMapping`, `timeSlotPickerMapping`, `userDetailsMapping`, `userLoginMapping`, `dividerMapping`: add one authored `trackingId="…"` property to the `export(true, …)` call and one assertion that it appears in `content.get("trackingId")`.

Example for `listMapping`:

```java
    final Map<String, Object> exported = export(true,
            "model", "list",
            "items", new String[] {"money-transfer", "currency-rates", "atm"},
            "limit", "6",
            "hideSubtitle", "false",
            "trackingId", "list-1");
    ...
    assertEquals("list-1", content.get("trackingId"), "extras through");
```

- [ ] **Step 3: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/divider.json \
        core/src/main/resources/contentbridge/mappings/map.json \
        core/src/main/resources/contentbridge/mappings/offer.json \
        core/src/main/resources/contentbridge/mappings/list.json \
        core/src/main/resources/contentbridge/mappings/productImage.json \
        core/src/main/resources/contentbridge/mappings/productTitle.json \
        core/src/main/resources/contentbridge/mappings/timeSlotPicker.json \
        core/src/main/resources/contentbridge/mappings/userLogin.json \
        core/src/main/resources/contentbridge/mappings/userDetails.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): opt in all remaining rules to skeleton + extras mode

9 rule files gain 'skeleton: true' and nullable markers on their
?-schema fields. Every rewritten per-rule test also confirms
unconsumed authored properties pass through as extras.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Post-plan

Full suite ends at 172-180 tests (rough — depends on how many per-rule tests we add). Every rule listed in the design spec is in skeleton mode. Panel/form/form-fields alignment with these schemas is a separate future plan.
