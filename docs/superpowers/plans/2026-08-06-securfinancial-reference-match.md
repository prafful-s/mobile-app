# SecurFinancial Reference Match Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the exporter-side gaps found comparing our v2 export against the SecurFinancial reference JSON — a screen-key bug, a `datePicker` shape bug, and ten missing v2 component types — without touching content authoring or the exporter's generic "any site, any component" design.

**Architecture:** Three independent surface areas, in dependency order: (1) `PageJsonExporterImpl.screenKey()`, a one-line fix with a wide, purely-mechanical test-assertion ripple; (2) `FormFieldFactory`'s `datePicker` branch, isolated; (3) the v2 `ComponentMapping` rule engine gains an optional `styles` field-rule list, then ten new JSON rule files under `core/src/main/resources/contentbridge/mappings/` supply the missing component shapes — same pattern as the six that already exist (`card.json`, `hero.json`, etc).

**Tech Stack:** Java 11, Sling Models, JUnit 5 + `io.wcm.testing.mock.aem` (`AemContext`, `JCR_MOCK` — no live AEM needed for any test in this plan), Jackson (mapping-rule JSON parsing, already wired).

## Global Constraints

- Every source file this plan touches ends in a real, human line ending; **do not** fight the existing `LF → CRLF` warning from Git on Windows — it's cosmetic, not a task blocker.
- `mvn test -pl core` must pass after every task before moving to the next. No AEM instance required.
- Field/style rule defaults in the new mapping files are best-effort guesses (see spec, "Field guessing" decision) — do not invent additional fields beyond what's documented per task; a guess corrected later is fine, a fabricated field that was never in the reference sample is not.
- `map.mapCoordinates` is explicitly out of scope (needs a `children` field-rule operator the engine doesn't have) — do not attempt it in Task 5.
- Do not rename, remove, or otherwise touch `action-button`, `product-card`, `insurance`, `loan-calculator`, `add-external`, `iframe`, `search`, `fileUpload`, or `radioGroup` — they stay exactly as they are.
- Commit after every task (not every step) — one commit per checked-off task, following the repo's existing commit style (see `git log --oneline`).

Spec: [`docs/superpowers/specs/2026-08-06-securfinancial-reference-match-design.md`](../specs/2026-08-06-securfinancial-reference-match-design.md)

---

## Task 1: `screenKey()` uses the page's node name, not its title

**Files:**
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/PageJsonExporterImpl.java:516-519`
- Modify (assertion updates only, no behavior change in these files): `core/src/test/java/com/dept/contentbridge/core/services/PageJsonExporterImplTest.java`, `ComponentMappingTest.java`, `ContentPathPrefixTest.java`, `EnvelopeMetaTest.java`, `FormBlockSchemaTest.java`, `FormBlockV2Test.java`, `SiteHostTest.java`, `WrapperAndCellTest.java`, `NavigationMenuTest.java`, `MenuVersusScreensTest.java`, `FranklinExportTest.java`, `HierarchicalSchemaTest.java`

**Interfaces:**
- Produces: `PageJsonExporterImpl.screenKey(Page)` now returns `page.getProperties().get("screenName", String.class)` when set, else `page.getName()` (was: else `page.getTitle()`/`page.getName()`). Nothing downstream changes signature — `screenKey` stays `private static String`.

- [ ] **Step 1: Write the failing test**

In `core/src/test/java/com/dept/contentbridge/core/services/PageJsonExporterImplTest.java`, add (the fixture's app-root page has node name `en` and title `Home` — see `core/src/test/resources/com/dept/contentbridge/core/content.json`):

```java
    @Test
    @DisplayName("screen key is the page's node name, not its title")
    void screenKeyIsNodeName() {
        final Map<String, Object> screens =
                map(exporter.export(page(ContentBridgeTestContext.APP_ROOT)).get("screens"));

        assertTrue(screens.containsKey("en"), "the app root's node name is 'en', its title is 'Home'");
        assertFalse(screens.containsKey("Home"), "title must not leak into the screen key: " + screens.keySet());
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=PageJsonExporterImplTest#screenKeyIsNodeName
```
Expected: FAIL — `screens.containsKey("en")` is false (current key is `"Home"`).

- [ ] **Step 3: Fix `screenKey()`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/PageJsonExporterImpl.java`, replace:

```java
    private static String screenKey(final Page page) {
        return StringUtils.defaultIfBlank(
                page.getProperties().get("screenName", String.class),
                StringUtils.defaultIfBlank(page.getTitle(), page.getName()));
```

with:

```java
    private static String screenKey(final Page page) {
        return StringUtils.defaultIfBlank(
                page.getProperties().get("screenName", String.class),
                page.getName());
```

(Leave the closing `}` and everything else in the file untouched — `EnvelopeMeta.screenMeta`'s `meta.put("title", ...)` still reads `page.getTitle()` separately and is not part of this change.)

- [ ] **Step 4: Run the new test, confirm it passes**

```bash
mvn test -pl core -Dtest=PageJsonExporterImplTest#screenKeyIsNodeName
```
Expected: PASS.

- [ ] **Step 5: Run the full suite to see the full blast radius**

```bash
mvn test -pl core
```
Expected: FAILURES in the 11 other files listed below — every one of them hardcodes a screen key that used to be a page *title* and is now that page's *node name*. This is expected; fix each in the next steps.

- [ ] **Step 6: Fix `PageJsonExporterImplTest.java`**

Three more `"Home"` occurrences in the same file (lines 101, 122, 144), each `map(screens.get("Home"))` or `map(screens.get("Home")).get("content")` — replace `"Home"` with `"en"` in all three. Also line 83's `assertTrue(menu.contains("Home"))` → `assertTrue(menu.contains("en"))`.

- [ ] **Step 7: Fix `ComponentMappingTest.java`**

Three occurrences of `.get("Home")` at (originally) lines 91, 180, 246 — replace `"Home"` with `"en"` in each. Line 57's page creation (`context.create().page(HOME, "", Map.of("jcr:title", "Home"))`) stays unchanged — only the lookups change.

- [ ] **Step 8: Fix `ContentPathPrefixTest.java`**

Two occurrences of `map(screens.get("Home"))` (originally lines 70, 145) — replace `"Home"` with `"en"`.

- [ ] **Step 9: Fix `EnvelopeMetaTest.java`**

Read the file fresh first (it has unrelated pre-existing uncommitted WIP — do not touch anything else in it). Three occurrences: `screenMeta("Home")` (twice) and `map(map(app.get("screens")).get("Home"))` — replace `"Home"` with `"en"` in all three, leaving everything else in the file exactly as it is.

- [ ] **Step 10: Fix `FormBlockSchemaTest.java`**

One occurrence: `map(screens.get("Home")).get("content")` → replace `"Home"` with `"en"`.

- [ ] **Step 11: Fix `FormBlockV2Test.java`**

Read the file fresh first (pre-existing uncommitted WIP — touch only this). One occurrence, in the `content(boolean v2)` helper: `map(map(app.get("screens")).get("Home"))` → replace `"Home"` with `"en"`.

- [ ] **Step 12: Fix `SiteHostTest.java`**

Two occurrences of `map(screens.get("Home"))` → replace `"Home"` with `"en"`.

- [ ] **Step 13: Fix `WrapperAndCellTest.java`**

Two occurrences (`collect(list(map(screens.get("Home")).get("content")), names);` and `map(list(map(screens.get("Home")).get("content")).get(0).get("content"));`) → replace `"Home"` with `"en"`.

- [ ] **Step 14: Fix `NavigationMenuTest.java`**

This file's fixture pages have title ≠ node name throughout (e.g. page created at `SITE + "/campaigns"` titled `"Campaigns"` → node name `campaigns`). Replace every literal in a menu/screen-key assertion with its node-name equivalent:

| literal (title) | replace with (node name) |
|---|---|
| `"Home"` | `"en"` (the site root, `SITE`) |
| `"Campaigns"` | `"campaigns"` |
| `"News"` | `"news"` |
| `"About Us"` | `"about-us"` |
| `"Unlisted"` | `"unlisted"` |
| `"Header"` | leave as-is — asserted `false`/excluded, unaffected by this change |
| `"Footer"` | leave as-is — same |
| `"One"` | `"one"` |
| `"Two"` | `"two"` |

Concretely: `menuComesFromNav` → `List.of("en", "campaigns", "news", "about-us")`. `unlistedPagesAreStillScreens` → `screens.containsKey("unlisted")`. `everyMenuEntryIsRenderable` needs no literal change (it iterates). `nonPageLinksAreSkipped` → `List.of("en", "news")`. `nestedMarkupInAnchors` → `.contains("news")`. `hrefVariants` → `List.of("en", "campaigns", "news")`. `duplicatesCollapse` → `List.of("en", "news")`. `homeScreenCanBeSuppressed` → `List.of("campaigns", "news", "about-us")` (unchanged content, already lowercase for these three — just campaigns/news/about-us as above). `fallbackWithoutNavPage` → `List.of("en", "one", "two")`. `fallbackWhenNavHasNoUsableLinks` → `.contains("campaigns")`. `linksFromARelocatedSiteStillResolve` → `List.of("en", "campaigns", "news")`. `explicitNavPathWins` → `List.of("en", "about-us")`.

Do not change the `Map.of("jcr:title", "Campaigns")`-style page-creation calls, the nav markup's anchor text (`>Campaigns<`), or `DisplayName` strings — only the Java-side assertions comparing against screen/menu keys.

- [ ] **Step 15: Fix `MenuVersusScreensTest.java`**

Same kind of rewrite. Pages: `SITE` (root, title `Home` → node `en`), `SITE + "/plans"` (title `Plans` → node `plans`), `SITE + "/contact"` (title `Contact` → node `contact`), `SITE + "/plan-detail"` (title `Plan Detail` → node `plan-detail`).

- `menuComesFromTheNavFragment`: `menu.contains("Plans")` → `menu.contains("plans")`; `menu.contains("Contact")` → `menu.contains("contact")`; `menu.contains("Plan Detail")` → `menu.contains("plan-detail")`.
- `screensHoldEveryPage`: `screens.containsKey("Home")` → `"en"`; `"Plans"` → `"plans"`; `"Contact"` → `"contact"`; `"Plan Detail"` → `"plan-detail"`.
- `furnitureIsExcluded`: leave `"Header"`/`"Footer"` as-is (unaffected — still excluded pages, this test only checks absence).
- `screenMetaRecordsMembership`: `screenMeta("Plans")` → `screenMeta("plans")`; `menu.indexOf("Plans")` → `menu.indexOf("plans")`; `screenMeta("Plan Detail")` → `screenMeta("plan-detail")`.

- [ ] **Step 16: Fix `FranklinExportTest.java`**

Fixture is `core/src/test/resources/com/dept/contentbridge/core/franklin.json`: page `/content/bodea/language-masters/en` (node `en`, title `English`), page `.../en/about-us` (node `about-us`, title `About Us`). Replace every `content("English")`, `componentNames("English")`, `screens.containsKey("English")` with `"en"` in place of `"English"` (10 occurrences), and `screens.containsKey("About Us")` / `componentNames("About Us")` with `"about-us"` in place of `"About Us"` (2 occurrences).

- [ ] **Step 17: Fix `HierarchicalSchemaTest.java`**

One occurrence: `screens.get("English")` → `screens.get("en")`.

- [ ] **Step 18: Run the full suite, confirm all green**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 19: Commit**

```bash
git add core/src/main/java/com/dept/contentbridge/core/services/impl/PageJsonExporterImpl.java \
        core/src/test/java/com/dept/contentbridge/core/services/PageJsonExporterImplTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/ContentPathPrefixTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/EnvelopeMetaTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/FormBlockSchemaTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/FormBlockV2Test.java \
        core/src/test/java/com/dept/contentbridge/core/services/SiteHostTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/WrapperAndCellTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/NavigationMenuTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/MenuVersusScreensTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/FranklinExportTest.java \
        core/src/test/java/com/dept/contentbridge/core/services/HierarchicalSchemaTest.java
git commit -m "fix(v2): screen key uses the page's node name, not its title

screenKey() fell back to page.getTitle() when no screenName was
authored. The reference contract keys screens by node name
('credit-card-basic', not 'Credit Card Basic'). Updates every test
that hardcoded a title-derived screen/menu key to match.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: `datePicker` shape fix

**Files:**
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/FormFieldFactory.java` (the `typedContent` method)
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/FormBlockV2Test.java` (`dateTypeBecomesDatePicker`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `FormFieldFactory.typedContent(...)` (private, unchanged signature) — for `component == DATE_PICKER` now emits exactly `mode, path, type, label, prefill, withDateFormatInLabel` and nothing else. For every other typed component (`textField`, `fileUpload`), output is unchanged.

- [ ] **Step 1: Extend the existing test first (red)**

In `core/src/test/java/com/dept/contentbridge/core/services/FormBlockV2Test.java`, replace `dateTypeBecomesDatePicker`:

```java
    @Test
    @DisplayName("a date-typed field becomes a datePicker - and only a date-typed one")
    void dateTypeBecomesDatePicker() {
        context.build().resource(ROOT + "/form",
                "sling:resourceType", "core/franklin/components/block/v1/block",
                "model", "form", "formName", "flight-search");

        final Map<String, Object> date = list(form().get("content")).stream()
                .filter(f -> "flight-date".equals(map(f.get("meta")).get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("flight-date field missing"));

        assertEquals("datePicker", map(date.get("meta")).get("component"));

        final Map<String, Object> content = map(date.get("content"));
        assertEquals(List.of("mode", "path", "type", "label", "prefill", "withDateFormatInLabel"),
                List.copyOf(content.keySet()), "a datePicker carries exactly these six keys");
        assertEquals("single", content.get("type"));
        assertEquals(Boolean.FALSE, content.get("withDateFormatInLabel"));
        assertFalse(content.containsKey("inputMode"), "nothing is typed into a datePicker");
        assertFalse(content.containsKey("value"), "a datePicker has no free-text value");
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=FormBlockV2Test#dateTypeBecomesDatePicker
```
Expected: FAIL — `content.keySet()` currently also contains `inputMode`, `multiline`, `isPassword`, `autoCorrect`, `leftElement`, `rightElement`, `enterKeyHint`, `value`, `placeholder`, `defaultValue`, and is missing `type`.

- [ ] **Step 3: Fix `typedContent()`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/FormFieldFactory.java`, replace:

```java
    /** Everything a user types into or picks a value for: text, password, date, file. */
    private static Map<String, Object> typedContent(final String component, final String path,
                                                    final String label, final boolean prefill,
                                                    final String inputMode, final boolean isPassword,
                                                    final String value, final String placeholder,
                                                    final String defaultValue) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("mode", MODE_OUTLINED);
        content.put("path", path);
        content.put("label", label);
        content.put("prefill", prefill);

        if (DATE_PICKER.equals(component)) {
            content.put("withDateFormatInLabel", false);
        }
        inputContent(content, inputMode, isPassword);

        content.put("value", StringUtils.defaultString(value));
        content.put("placeholder", StringUtils.defaultString(placeholder));
        content.put("defaultValue", StringUtils.defaultString(defaultValue));
        return content;
    }
```

with:

```java
    /** Everything a user types into or picks a value for: text, password, date, file. */
    private static Map<String, Object> typedContent(final String component, final String path,
                                                    final String label, final boolean prefill,
                                                    final String inputMode, final boolean isPassword,
                                                    final String value, final String placeholder,
                                                    final String defaultValue) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("mode", MODE_OUTLINED);
        content.put("path", path);

        // A date picker is picked from a calendar, not typed into - none of the typed-input keys
        // below apply to it, and it carries no free-text value/placeholder/defaultValue either.
        if (DATE_PICKER.equals(component)) {
            content.put("type", "single");
            content.put("label", label);
            content.put("prefill", prefill);
            content.put("withDateFormatInLabel", false);
            return content;
        }

        content.put("label", label);
        content.put("prefill", prefill);
        inputContent(content, inputMode, isPassword);

        content.put("value", StringUtils.defaultString(value));
        content.put("placeholder", StringUtils.defaultString(placeholder));
        content.put("defaultValue", StringUtils.defaultString(defaultValue));
        return content;
    }
```

- [ ] **Step 4: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=FormBlockV2Test#dateTypeBecomesDatePicker
```
Expected: PASS.

- [ ] **Step 5: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS. (`contentShapeFollowsTheWidget` in the same file iterates every field of `name-change-request`, which has no date-typed field — confirmed by inspecting `core/src/main/resources/contentbridge/forms/name-change-request.json` — so it is not affected.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/dept/contentbridge/core/services/impl/FormFieldFactory.java \
        core/src/test/java/com/dept/contentbridge/core/services/FormBlockV2Test.java
git commit -m "fix(v2): datePicker no longer carries typed-input keys

FormFieldFactory.typedContent() emitted inputMode/multiline/isPassword/
autoCorrect/leftElement/rightElement/enterKeyHint/value/placeholder/
defaultValue for datePicker fields, none of which apply to a
calendar-picked value, and was missing the reference contract's
'type': 'single'.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: mapping-rule engine gains `styles`, plus `contentCards.json`

**Files:**
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentMapping.java`
- Modify: `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentTreeWalker.java`
- Create: `core/src/main/resources/contentbridge/mappings/contentCards.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

**Interfaces:**
- Produces: `ComponentMapping.applyStyles(Map<String, Object> source): Map<String, Object>` — package-private, same shape/contract as the existing `apply()` but targets `styles` and is **not** gated on any field being authored (always returns declared defaults for absent keys). `ComponentMapping.of(Map<String, Object> definition)` now also reads an optional `"styles"` array from the rule file, parsed identically to `"fields"`.
- Consumes (from Task 1/2): nothing.

- [ ] **Step 1: Write the failing test**

Append to `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java` (reuses the file's existing `export`/`content`/`component` helpers):

```java
    @Test
    @DisplayName("a mapping rule can also project a sibling 'styles' object")
    void mappingProjectsStyles() {
        final Map<String, Object> exported = export(true,
                "model", "contentCards",
                "surface", "sf_home_screen_cc",
                "color", "#363232",
                "borderRadius", "9");

        assertEquals("contentCards", component(exported));
        assertEquals("sf_home_screen_cc", content(exported).get("surface"));

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals("#363232", styles.get("color"));
        assertEquals(9L, styles.get("borderRadius"), "declared as type: number");
        assertEquals("lightgrey", styles.get("borderColor"), "unauthored style keys still get their default");
        assertEquals("white", styles.get("backgroundColor"));
    }

    @Test
    @DisplayName("mappings/index.json and the mappings directory agree")
    void indexMatchesTheDirectory() throws Exception {
        final java.net.URL dir = getClass().getResource("/contentbridge/mappings");
        assertNotNull(dir, "mapping rule directory is missing from the classpath");

        final java.io.File[] files = new java.io.File(dir.toURI()).listFiles(
                (d, n) -> n.endsWith(".json") && !"index.json".equals(n));
        assertNotNull(files);

        final java.util.Set<String> onDisk = new java.util.TreeSet<>();
        for (final java.io.File file : files) {
            onDisk.add(file.getName().replace(".json", ""));
        }

        final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        final java.util.List<String> indexed = mapper.readValue(
                getClass().getResourceAsStream("/contentbridge/mappings/index.json"),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });

        assertEquals(onDisk, new java.util.TreeSet<>(indexed),
                "index.json is out of step with the files - add or remove the name there too");
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#mappingProjectsStyles,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: `mappingProjectsStyles` FAILS (no `contentCards` rule exists yet, `exported.get("styles")` is `null`). `indexMatchesTheDirectory` currently PASSES (6 files match 6 index entries) — that's fine, it stays green through this step and starts pulling weight from Task 4 onward.

- [ ] **Step 3: Add `styles` support to `ComponentMapping`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentMapping.java`, replace the field declarations and constructor:

```java
    private final List<String> matches;
    private final String component;
    private final List<FieldRule> fields;

    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
    }
```

with:

```java
    private final List<String> matches;
    private final String component;
    private final List<FieldRule> fields;
    private final List<FieldRule> styleFields;

    private ComponentMapping(final List<String> matches, final String component,
                             final List<FieldRule> fields, final List<FieldRule> styleFields) {
        this.matches = matches;
        this.component = component;
        this.fields = fields;
        this.styleFields = styleFields;
    }
```

Replace `of()`:

```java
    static ComponentMapping of(final Map<String, Object> definition) {
        final String component = trimmed(definition.get("component"));
        final List<String> matches = names(definition.get("match"));
        if (StringUtils.isBlank(component) || matches.isEmpty()) {
            return null;
        }
        final List<FieldRule> fields = new ArrayList<>();
        final Object declared = definition.get("fields");
        if (declared instanceof List) {
            for (final Object entry : (List<?>) declared) {
                if (entry instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final FieldRule rule = FieldRule.of((Map<String, Object>) entry);
                    if (rule != null) {
                        fields.add(rule);
                    }
                }
            }
        }
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields));
    }
```

with:

```java
    static ComponentMapping of(final Map<String, Object> definition) {
        final String component = trimmed(definition.get("component"));
        final List<String> matches = names(definition.get("match"));
        if (StringUtils.isBlank(component) || matches.isEmpty()) {
            return null;
        }
        final List<FieldRule> fields = fieldRules(definition.get("fields"));
        final List<FieldRule> styleFields = fieldRules(definition.get("styles"));
        return new ComponentMapping(Collections.unmodifiableList(matches), component,
                Collections.unmodifiableList(fields), Collections.unmodifiableList(styleFields));
    }

    /** Parses either a rule file's {@code fields} or its optional {@code styles} - same shape. */
    private static List<FieldRule> fieldRules(final Object declared) {
        final List<FieldRule> rules = new ArrayList<>();
        if (declared instanceof List) {
            for (final Object entry : (List<?>) declared) {
                if (entry instanceof Map) {
                    @SuppressWarnings("unchecked")
                    final FieldRule rule = FieldRule.of((Map<String, Object>) entry);
                    if (rule != null) {
                        rules.add(rule);
                    }
                }
            }
        }
        return rules;
    }
```

Add a new method right after `apply()` (after its closing brace, before `isPresent`):

```java
    /**
     * Projects authored properties onto the rule's declared {@code styles}, the sibling of
     * {@code content} a handful of components carry.
     *
     * <p>Unlike {@link #apply}, not gated on anything being authored: a component this rule already
     * claimed (its content mapping succeeded) still gets its declared style defaults even where no
     * style property was actually set, the same way individual {@code fields} entries already
     * default-fill an unauthored key.</p>
     *
     * @param source the component's authored properties
     * @return the target styles in the order the rule declares, or an empty map when the rule
     *         declares no {@code styles} at all
     */
    Map<String, Object> applyStyles(final Map<String, Object> source) {
        final Map<String, Object> target = new LinkedHashMap<>();
        for (final FieldRule field : styleFields) {
            Object value = field.fromSource(source);
            if (!isPresent(value)) {
                value = field.fallback();
            }
            if (isPresent(value)) {
                target.put(field.getTarget(), value);
            }
        }
        return target;
    }
```

- [ ] **Step 4: Wire `styles` into `ComponentTreeWalker`**

In `core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentTreeWalker.java`, replace:

```java
            final String authoredName = model.getComponentName();
            String componentName = authoredName;
            Map<String, Object> content = authored;
```

with:

```java
            final String authoredName = model.getComponentName();
            String componentName = authoredName;
            Map<String, Object> content = authored;
            Map<String, Object> styles = Collections.emptyMap();
```

Replace:

```java
            if (mapping != null) {
                final Map<String, Object> mapped = mapping.apply(authored);
                if (mapped.isEmpty()) {
                    // The rule matched a component with none of its source fields authored. An empty
                    // banner tells a client less than the authored shape does, so this one component
                    // is exported unmapped rather than emptied.
                    LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped",
                            authoredName, child.getPath());
                } else {
                    componentName = mapping.getComponent();
                    content = mapped;
                }
            }
```

with:

```java
            if (mapping != null) {
                final Map<String, Object> mapped = mapping.apply(authored);
                if (mapped.isEmpty()) {
                    // The rule matched a component with none of its source fields authored. An empty
                    // banner tells a client less than the authored shape does, so this one component
                    // is exported unmapped rather than emptied.
                    LOG.debug("Rule for '{}' produced nothing at {} - exporting it unmapped",
                            authoredName, child.getPath());
                } else {
                    componentName = mapping.getComponent();
                    content = mapped;
                    styles = mapping.applyStyles(authored);
                }
            }
```

Replace:

```java
            entry.put(SchemaConstants.KEY_META, meta);
            entry.put(SchemaConstants.KEY_CONTENT, content);

            // v1 only: the field definitions travel alongside the authored content, so a client can
            // render the form without a second call or a hard-coded copy of the schema.
```

with:

```java
            entry.put(SchemaConstants.KEY_META, meta);
            if (!styles.isEmpty()) {
                entry.put(SchemaConstants.KEY_STYLES, styles);
            }
            entry.put(SchemaConstants.KEY_CONTENT, content);

            // v1 only: the field definitions travel alongside the authored content, so a client can
            // render the form without a second call or a hard-coded copy of the schema.
```

(`java.util.Collections` is already imported in this file — used by `resolveFormSchema`'s `Collections.emptyList()`.)

- [ ] **Step 5: Create `contentCards.json`**

Create `core/src/main/resources/contentbridge/mappings/contentCards.json`:

```json
{
  "match": ["contentCards"],
  "component": "contentCards",
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

- [ ] **Step 6: Add it to the index**

In `core/src/main/resources/contentbridge/mappings/index.json`, add `"contentCards"` to the array (any position — `ComponentMappings.load()` sorts into a `TreeMap`):

```json
[
  "hero",
  "card",
  "text",
  "title",
  "button",
  "image",
  "contentCards"
]
```

- [ ] **Step 7: Run both new tests, confirm they pass**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#mappingProjectsStyles,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 8: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentMapping.java \
        core/src/main/java/com/dept/contentbridge/core/services/impl/ComponentTreeWalker.java \
        core/src/main/resources/contentbridge/mappings/contentCards.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): mapping rules can project a sibling 'styles' object

ComponentMapping gains an optional 'styles' field-rule list, parsed
and applied the same way as 'fields' but targeting a sibling 'styles'
key and not gated on anything being authored - a component's declared
style defaults always come through. First consumer: contentCards.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: `list.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/list.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

**Interfaces:** none new — consumes `ComponentMapping`/`applyStyles` from Task 3 unchanged.

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("a list carries its authored items and its numeric/boolean defaults")
    void listMapping() {
        final Map<String, Object> exported = export(true,
                "model", "list",
                "items", new String[] {"money-transfer", "currency-rates", "atm"},
                "limit", "6",
                "hideSubtitle", "false");

        assertEquals("list", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals(List.of("money-transfer", "currency-rates", "atm"), content.get("items"));
        assertEquals(6L, content.get("limit"), "declared as type: number");
        assertEquals(Boolean.FALSE, content.get("hideSubtitle"));
        assertEquals("none", content.get("sort"), "unauthored, falls back to its default");
        assertEquals("horizontal", content.get("layout"));
        assertEquals("screenList", content.get("listType"));

        assertEquals(8L, map(exported.get("styles")).get("gap"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#listMapping
```
Expected: FAIL (`component(exported)` is `"list"` via generic fallback already, but `content.get("items")` etc. are absent — no rule exists yet, so `mapping` is `null` and nothing is coerced/defaulted; `styles` is entirely absent).

- [ ] **Step 3: Create `list.json`**

Create `core/src/main/resources/contentbridge/mappings/list.json`:

```json
{
  "match": ["list"],
  "component": "list",
  "fields": [
    { "target": "sort", "from": "sort", "default": "none" },
    { "target": "items", "from": "items" },
    { "target": "limit", "from": "limit", "type": "number", "default": 6 },
    { "target": "filter", "from": "filter", "default": "" },
    { "target": "layout", "from": "layout", "default": "horizontal" },
    { "target": "listType", "from": "listType", "default": "screenList" },
    { "target": "imageWidth", "from": "imageWidth", "type": "number", "default": 100 },
    { "target": "itemHeight", "from": "itemHeight", "type": "number", "default": 100 },
    { "target": "itemsInRow", "from": "itemsInRow", "type": "number", "default": 1 },
    { "target": "imageResize", "from": "imageResize", "default": "contain" },
    { "target": "hideSubtitle", "from": "hideSubtitle", "type": "boolean", "default": false },
    { "target": "itemRenderer", "from": "itemRenderer", "default": "stacked" },
    { "target": "filterProperty", "from": "filterProperty", "default": "Category" },
    { "target": "titleFontWeight", "from": "titleFontWeight", "default": "regular" },
    { "target": "subtitleFontWeight", "from": "subtitleFontWeight", "default": "light" }
  ],
  "styles": [
    { "target": "gap", "from": "gap", "type": "number", "default": 8 },
    { "target": "color", "from": "color", "default": "" }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"list"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#listMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/list.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'list' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: `map.json` (no `mapCoordinates` — flagged, out of scope)

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/map.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("a map carries its flat fields and styles - mapCoordinates is a known gap")
    void mapMapping() {
        final Map<String, Object> exported = export(true,
                "model", "map",
                "text", "ATM",
                "buttonLabel", "Find one");

        assertEquals("map", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("ATM", content.get("text"));
        assertEquals("Find one", content.get("buttonLabel"));
        assertEquals(Boolean.FALSE, content.get("showInModal"));
        assertEquals("contained", content.get("buttonVariant"));
        assertFalse(content.containsKey("mapCoordinates"),
                "child-row aggregation is a known gap, not attempted here");

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals("blue", styles.get("color"));
        assertEquals("green", styles.get("locationColor"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#mapMapping
```
Expected: FAIL — no rule yet, so none of the defaults/coercions or `styles` are produced.

- [ ] **Step 3: Create `map.json`**

Create `core/src/main/resources/contentbridge/mappings/map.json`:

```json
{
  "match": ["map"],
  "component": "map",
  "fields": [
    { "comment": "mapCoordinates intentionally omitted - needs child-row aggregation the mapping engine does not have yet. See the design spec.",
      "target": "text", "from": "text" },
    { "target": "buttonLabel", "from": "buttonLabel" },
    { "target": "showInModal", "from": "showInModal", "type": "boolean", "default": false },
    { "target": "buttonVariant", "from": "buttonVariant", "default": "contained" }
  ],
  "styles": [
    { "target": "color", "from": "color", "default": "blue" },
    { "target": "locationColor", "from": "locationColor", "default": "green" }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"map"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#mapMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/map.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'map' component mapping (flat fields only)

mapCoordinates (array of {label, latitude, longitude}) needs
child-row aggregation the mapping engine doesn't have yet - out of
scope per the design spec, flagged in the rule file itself.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: `offer.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/offer.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("an offer carries its authored link/surface and its defaults")
    void offerMapping() {
        final Map<String, Object> exported = export(true,
                "model", "offer",
                "linkTo", "insurance",
                "surface", "sf_home_offer");

        assertEquals("offer", component(exported));

        final Map<String, Object> content = content(exported);
        assertEquals("insurance", content.get("linkTo"));
        assertEquals("sf_home_offer", content.get("surface"));
        assertEquals(0.34, content.get("opacity"));
        assertEquals(Boolean.TRUE, content.get("showTitle"));
        assertEquals("cover", content.get("imageResize"));
        assertEquals("titleLarge", content.get("titleVariant"));
        assertEquals("center", content.get("horizontalAlignment"));

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals("#FFF", styles.get("color"));
        assertEquals(200L, styles.get("height"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#offerMapping
```
Expected: FAIL.

- [ ] **Step 3: Create `offer.json`**

Create `core/src/main/resources/contentbridge/mappings/offer.json`:

```json
{
  "match": ["offer"],
  "component": "offer",
  "fields": [
    { "target": "linkTo", "from": "linkTo" },
    { "target": "opacity", "from": "opacity", "type": "number", "default": 0.34 },
    { "target": "surface", "from": "surface" },
    { "target": "showTitle", "from": "showTitle", "type": "boolean", "default": true },
    { "target": "imageResize", "from": "imageResize", "default": "cover" },
    { "target": "titleVariant", "from": "titleVariant", "default": "titleLarge" },
    { "target": "fallbackImage", "from": "fallbackImage" },
    { "target": "subtitleVariant", "from": "subtitleVariant", "default": "titleMedium" },
    { "target": "contentAlignment", "from": "contentAlignment", "default": "center" },
    { "target": "showDebugMessage", "from": "showDebugMessage", "type": "boolean", "default": false },
    { "target": "verticalAlignment", "from": "verticalAlignment", "default": "bottom" },
    { "target": "horizontalAlignment", "from": "horizontalAlignment", "default": "center" }
  ],
  "styles": [
    { "target": "color", "from": "color", "default": "#FFF" },
    { "target": "height", "from": "height", "type": "number", "default": 200 },
    { "target": "borderRadius", "from": "borderRadius", "default": "0" }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"offer"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#offerMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/offer.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'offer' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 7: `productImage.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/productImage.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`. Note: `ComponentMapping.apply()` requires at least one field to read a real authored value, or it returns empty and the component falls through unmapped (the engine's existing "empty projection is abandoned" rule — see `ComponentMappingTest#emptyProjectionIsAbandoned`), so the test authors `fullWidth` explicitly rather than relying only on defaults:

```java
    @Test
    @DisplayName("a productImage takes its layout defaults and its authored height")
    void productImageMapping() {
        final Map<String, Object> exported = export(true,
                "model", "productImage",
                "fullWidth", "true");

        assertEquals("productImage", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals(Boolean.TRUE, content.get("fullWidth"));
        assertEquals("contain", content.get("imageResize"));
        assertEquals(250L, map(exported.get("styles")).get("height"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#productImageMapping
```
Expected: FAIL.

- [ ] **Step 3: Create `productImage.json`**

Create `core/src/main/resources/contentbridge/mappings/productImage.json`:

```json
{
  "match": ["productImage"],
  "component": "productImage",
  "fields": [
    { "target": "fullWidth", "from": "fullWidth", "type": "boolean", "default": true },
    { "target": "imageResize", "from": "imageResize", "default": "contain" }
  ],
  "styles": [
    { "target": "height", "from": "height", "type": "number", "default": 250 }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"productImage"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#productImageMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/productImage.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'productImage' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 8: `productTitle.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/productTitle.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("a productTitle takes its size/weight/alignment defaults")
    void productTitleMapping() {
        final Map<String, Object> exported = export(true,
                "model", "productTitle",
                "textAlignment", "center");

        assertEquals("productTitle", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("large", content.get("size"));
        assertEquals("bold", content.get("weight"));
        assertEquals("center", content.get("textAlignment"));
        assertEquals("#FFF", map(exported.get("styles")).get("color"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#productTitleMapping
```
Expected: FAIL.

- [ ] **Step 3: Create `productTitle.json`**

Create `core/src/main/resources/contentbridge/mappings/productTitle.json`:

```json
{
  "match": ["productTitle"],
  "component": "productTitle",
  "fields": [
    { "target": "size", "from": "size", "default": "large" },
    { "target": "weight", "from": "weight", "default": "bold" },
    { "target": "textAlignment", "from": "textAlignment", "default": "left" }
  ],
  "styles": [
    { "target": "color", "from": "color", "default": "#FFF" }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"productTitle"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#productTitleMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/productTitle.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'productTitle' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 9: `timeSlotPicker.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/timeSlotPicker.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("a timeSlotPicker carries its authored path/options and its defaults")
    void timeSlotPickerMapping() {
        final Map<String, Object> exported = export(true,
                "model", "timeSlotPicker",
                "path", "appointment.dateTime",
                "timeOptions", new String[] {"09:00", "10:00", "11:00"});

        assertEquals("timeSlotPicker", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("appointment.dateTime", content.get("path"));
        assertEquals(List.of("09:00", "10:00", "11:00"), content.get("timeOptions"));
        assertEquals(Boolean.TRUE, content.get("prefill"));
        assertEquals(3L, content.get("numberOfDays"));
        assertEquals(3L, content.get("numberOfTimeColumns"));

        final Map<String, Object> styles = map(exported.get("styles"));
        assertEquals(8L, styles.get("gap"));
        assertEquals(8L, styles.get("padding"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#timeSlotPickerMapping
```
Expected: FAIL.

- [ ] **Step 3: Create `timeSlotPicker.json`**

Create `core/src/main/resources/contentbridge/mappings/timeSlotPicker.json`:

```json
{
  "match": ["timeSlotPicker"],
  "component": "timeSlotPicker",
  "fields": [
    { "target": "path", "from": "path" },
    { "target": "prefill", "from": "prefill", "type": "boolean", "default": true },
    { "target": "timeOptions", "from": "timeOptions" },
    { "target": "defaultValue", "from": "defaultValue", "default": "" },
    { "target": "numberOfDays", "from": "numberOfDays", "type": "number", "default": 3 },
    { "target": "numberOfTimeColumns", "from": "numberOfTimeColumns", "type": "number", "default": 3 }
  ],
  "styles": [
    { "target": "gap", "from": "gap", "type": "number", "default": 8 },
    { "target": "padding", "from": "padding", "type": "number", "default": 8 }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"timeSlotPicker"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#timeSlotPickerMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/timeSlotPicker.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'timeSlotPicker' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 10: `userDetails.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/userDetails.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("userDetails coerces its authored flag to a real boolean")
    void userDetailsMapping() {
        final Map<String, Object> exported = export(true,
                "model", "userDetails",
                "showLogoutButton", "false");

        assertEquals("userDetails", component(exported));
        assertEquals(Boolean.FALSE, content(exported).get("showLogoutButton"),
                "Edge Delivery writes booleans as strings; the rule coerces them");
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#userDetailsMapping
```
Expected: FAIL (`content(exported).get("showLogoutButton")` is the raw string `"false"`, not the boolean `Boolean.FALSE`, since nothing coerces it without a rule).

- [ ] **Step 3: Create `userDetails.json`**

Create `core/src/main/resources/contentbridge/mappings/userDetails.json`:

```json
{
  "match": ["userDetails"],
  "component": "userDetails",
  "fields": [
    { "target": "showLogoutButton", "from": "showLogoutButton", "type": "boolean", "default": true }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"userDetails"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#userDetailsMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/userDetails.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'userDetails' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 11: `userLogin.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/userLogin.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("userLogin takes its authored labels and its defaults")
    void userLoginMapping() {
        final Map<String, Object> exported = export(true,
                "model", "userLogin",
                "title", "Sign in");

        assertEquals("userLogin", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("Sign in", content.get("title"));
        assertEquals("Password", content.get("passwordLabel"));
        assertEquals("Email", content.get("usernameLabel"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#userLoginMapping
```
Expected: FAIL (no rule yet, so `passwordLabel`/`usernameLabel` are simply absent, not defaulted).

- [ ] **Step 3: Create `userLogin.json`**

Create `core/src/main/resources/contentbridge/mappings/userLogin.json`:

```json
{
  "match": ["userLogin"],
  "component": "userLogin",
  "fields": [
    { "target": "title", "from": "title", "default": "Login" },
    { "target": "passwordLabel", "from": "passwordLabel", "default": "Password" },
    { "target": "usernameLabel", "from": "usernameLabel", "default": "Email" }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"userLogin"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#userLoginMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/userLogin.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'userLogin' component mapping

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 12: `divider.json`

**Files:**
- Create: `core/src/main/resources/contentbridge/mappings/divider.json`
- Modify: `core/src/main/resources/contentbridge/mappings/index.json`
- Modify: `core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java`

Note: `DividerModel` (the existing v1 Sling Model, `core/src/main/java/com/dept/contentbridge/core/models/impl/DividerModel.java`) is untouched by this task — it only ever matches resource type `contentbridge/components/divider`, which Edge Delivery/Franklin content never has, so it stays dead code for v2 and a live, correct path for classic-authored v1 content. This mapping rule is the v2 path, reached only through Franklin's `separator` block name.

- [ ] **Step 1: Write the failing test**

Append to `ComponentMappingTest.java`:

```java
    @Test
    @DisplayName("a separator block becomes a divider with the reference's style defaults")
    void dividerMapping() {
        final Map<String, Object> exported = export(true,
                "model", "separator",
                "showLine", "false");

        assertEquals("divider", component(exported));
        final Map<String, Object> content = content(exported);
        assertEquals("S", content.get("size"));
        assertEquals("S", content.get("length"));
        assertEquals("normal", content.get("variant"));
        assertEquals(Boolean.FALSE, content.get("showLine"));
        assertEquals("center", content.get("alignment"));
        assertEquals("vertical", content.get("orientation"));
    }
```

- [ ] **Step 2: Run it, confirm it fails**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#dividerMapping
```
Expected: FAIL — `component(exported)` is `"separator"` (the raw authored name, no rule maps it to `"divider"` yet).

- [ ] **Step 3: Create `divider.json`**

Create `core/src/main/resources/contentbridge/mappings/divider.json`:

```json
{
  "match": ["divider", "separator"],
  "component": "divider",
  "fields": [
    { "target": "size", "from": "size", "default": "S" },
    { "target": "length", "from": "length", "default": "S" },
    { "target": "variant", "from": "variant", "default": "normal" },
    { "target": "showLine", "from": "showLine", "type": "boolean", "default": false },
    { "target": "alignment", "from": "alignment", "default": "center" },
    { "target": "orientation", "from": "orientation", "default": "vertical" }
  ]
}
```

- [ ] **Step 4: Add it to the index**

Add `"divider"` to `core/src/main/resources/contentbridge/mappings/index.json`.

- [ ] **Step 5: Run the test, confirm it passes**

```bash
mvn test -pl core -Dtest=ComponentMappingTest#dividerMapping,ComponentMappingTest#indexMatchesTheDirectory
```
Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
mvn test -pl core
```
Expected: BUILD SUCCESS, 0 failures — this is the last task in the plan.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/contentbridge/mappings/divider.json \
        core/src/main/resources/contentbridge/mappings/index.json \
        core/src/test/java/com/dept/contentbridge/core/services/ComponentMappingTest.java
git commit -m "feat(v2): add 'divider' component mapping

Reached through Franklin's 'separator' block name (README already
names it in the Franklin vocabulary) or an explicit 'divider' model.
The existing v1 DividerModel Sling Model is untouched - Edge Delivery
content never carries its resource type, so the two paths don't
collide.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Post-plan (not part of this plan — flag, don't do)

- `productDescription` needs no mapping file (Change 4 in the spec) — the generic Franklin fallback already produces its always-empty `content: {}` shape once a block's `model` property is `productDescription`. No task above touches it; if a future review asks "where's productDescription", point here.
- `map.mapCoordinates` remains unimplemented (Task 5) — the next real step, if ever picked up, is a `children` `FieldRule` operator per the design spec's "out of scope" section, not a re-opening of Task 5.
