# Matching the SecurFinancial reference JSON — design

**Date:** 2026-08-06
**Branch:** feature/page-to-scheam-phase4
**Status:** approved (pending spec review)

## Goal

`securfinancial.json` (Adobe's reference/demo export, brand "SecurFinancial") and our own v2
export of `/content/secur-financial` (brand "Securbank") are two independently authored apps —
not the same content under different names. Diffing their component vocabularies surfaced real
gaps and one real bug in *our exporter*, independent of whose content is whose:

1. A screen-key bug: `screenKey()` falls back to page **title**, not node name.
2. Twelve component types the reference contract uses that our exporter has no shape for.
3. Two small gaps in the v2 mapping-rule engine that block several of those twelve.

This spec covers only the exporter. It does not re-author content, does not rename or remove any
of our own extra component types (`action-button`, `product-card`, `insurance`,
`loan-calculator`, `add-external`, `iframe`, `search`, `fileUpload`), and does not reconcile
`title`/`button`/`banner` count differences between the two apps — those are different content,
not a bug.

## Background: how v2 already extends

Confirmed from code, not assumed:

- v2 (`?schema=v2`) is hierarchical. Sling-Model-backed components (`BannerModel`, `CardModel`,
  `TitleModel`, `HtmlTextModel`, `ImageModel`, `DividerModel` — all `AbstractComponentModel`
  subclasses) bypass the mapping engine entirely ("speaks the target vocabulary already").
- Everything else — Edge Delivery/Franklin-authored, or any resource type with no dedicated
  model — is read generically (`GenericComponent`: `model` property → component name,
  `modelFields`/every non-bookkeeping property → content), then optionally rewritten by a
  **JSON rule file** under `core/src/main/resources/contentbridge/mappings/`
  (`ComponentMapping`/`ComponentMappings`/`FieldRule`), listed in that directory's `index.json`.
  This is the extension point — same "data, not code" pattern the form schemas already use
  (`core/src/main/resources/contentbridge/forms/`).
- Form fields (`textField`, `dropdown`, `checkboxGroup`, `radioGroup`, `datePicker`,
  `fileUpload`) are a third, disjoint path: `FormFieldFactory`/`FormBlockBuilder`, reached only
  when `ComponentTreeWalker.isFormBlock()` recognizes the block as a form.

Verified against the real local content (`/content/secur-financial`, 42 pages via QueryBuilder,
max depth 3): none of the twelve missing types are authored anywhere yet. There is no real
example to reverse-engineer property names from — mapping-rule `from`/`first` candidates below
are best-effort guesses, the same way `button.json`/`title.json` already guess multiple candidate
names. Confirmed acceptable per prior discussion; correct later once real content exists.

## Change 1 — screen key uses node name, not title

[`PageJsonExporterImpl.screenKey()`](../../../core/src/main/java/com/dept/contentbridge/core/services/impl/PageJsonExporterImpl.java):

```java
// current
return StringUtils.defaultIfBlank(
        page.getProperties().get("screenName", String.class),
        StringUtils.defaultIfBlank(page.getTitle(), page.getName()));

// target
return StringUtils.defaultIfBlank(
        page.getProperties().get("screenName", String.class),
        page.getName());
```

`meta.title` (in `EnvelopeMeta.screenMeta`) is untouched — it keeps reading `page.getTitle()`.
Only the map key / `meta.name` / menu entries change from "Credit Card Basic" to
`credit-card-basic`.

**"Flatten every child page" — already correct, no change.** `collectScreens`/
`collectDescendants` already walks every descendant recursively (not just nav-linked pages) up to
`maxDepth` (default 3). Verified against the real tree: 42 pages, max depth 3 relative to the
site root — already fully captured today. The `cq:pages` query idea from the original ask is
exactly what this recursion already does.

## Change 2 — `datePicker` shape fix

`datePicker` is not missing — `FormFieldFactory` already wires it (config `type: "date"`,
authored `fieldType: datepicker`/`dateinput`) — but its shape is wrong. Reference:

```json
{ "mode": "outlined", "path": "person.birthDate", "type": "single",
  "label": "Data of birth", "prefill": true, "withDateFormatInLabel": false }
```

Ours currently also emits `inputMode`/`multiline`/`isPassword`/`autoCorrect`/`leftElement`/
`rightElement`/`enterKeyHint`/`value`/`placeholder`/`defaultValue` — none of which belong on a
date picker (nothing is typed into it). Fix in `FormFieldFactory.typedContent()`: when
`component == DATE_PICKER`, emit only `mode, path, type("single"), label, prefill,
withDateFormatInLabel`, skip `inputContent(...)` and the three input-value keys entirely.

## Change 3 — mapping-rule engine: optional `styles`

Four of the missing types carry a `styles` object (a sibling of `content`) with direct authored
values (`"borderRadius": 9`, `"color": "#363232"`) — not `StyleResolver`'s token table (that's
section-level spacing tokens like `sec-spacing="section-large"`, only ever applied to `panel`
wrappers today).

Add an optional second field-rule list to a mapping file, reusing `FieldRule` unchanged:

```json
{
  "match": ["contentCards"],
  "component": "contentCards",
  "fields": [ ... ],
  "styles": [
    { "target": "color", "from": "color" },
    { "target": "borderColor", "from": "borderColor", "default": "lightgrey" }
  ]
}
```

`ComponentMapping`:
- `of()` parses `definition.get("styles")` the same way it parses `fields`, into a second
  `List<FieldRule> styleFields`.
- new `Map<String, Object> applyStyles(Map<String, Object> source)` — identical logic to
  `apply()` (per-field: authored value, else fallback, emit if present) but **not** gated on
  `anyAuthored` — a component whose content mapping succeeded still gets its declared style
  defaults even if none of its styles were actually authored, consistent with how `fields`
  already default-fills unauthored keys.

`ComponentTreeWalker`: right after a non-empty `content = mapping.apply(authored)`, also compute
`mapping.applyStyles(authored)` and `entry.put(KEY_STYLES, styles)` when non-empty — mirroring
what `emitPanel` already does for panels.

## Change 4 — new mapping-rule files

`core/src/main/resources/contentbridge/mappings/index.json` gains 10 entries. **Not 12** — two of
the twelve need no new file:

- **`datePicker`** — handled entirely by Change 2, not the mapping engine.
- **`productDescription`** — reference's content is always `{}` (see sample below). The generic
  Franklin fallback already produces exactly that (component name from the `model` property,
  empty content when the block has no other authored properties) — no rule needed.

| file | `match` | notes |
|---|---|---|
| `divider.json` | `divider`, `separator` | README already names `separator` in the Franklin vocabulary |
| `contentCards.json` | `contentCards` | has `styles` |
| `list.json` | `list` | `items` is a plain multi-value property → passes through as a list already (`GenericComponent.normalize` converts `String[]` to a `List`); has `styles` |
| `map.json` | `map` | **`mapCoordinates` intentionally omitted** — needs child-row aggregation the engine doesn't have; flagged as a known gap per prior agreement. Flat fields + `styles` only |
| `offer.json` | `offer` | has `styles`; reference sample also repeats several keys at the block's own top level outside `content` — treated as an authoring inconsistency in the source system, not replicated |
| `productImage.json` | `productImage` | has `styles` |
| `productTitle.json` | `productTitle` | has `styles` |
| `timeSlotPicker.json` | `timeSlotPicker` | `timeOptions` same multi-value passthrough as `list.items`; has `styles` |
| `userDetails.json` | `userDetails` | single boolean field — still needs a rule purely for `type: boolean` coercion (Franklin stores flags as the strings `"true"`/`"false"`) |
| `userLogin.json` | `userLogin` | three flat string fields |

### Field tables (best-effort candidates; defaults = reference's own sample values)

**divider** — component `divider`
| target | source | type | default |
|---|---|---|---|
| size | `size` | | `S` |
| length | `length` | | `S` |
| variant | `variant` | | `normal` |
| showLine | `showLine` | boolean | `false` |
| alignment | `alignment` | | `center` |
| orientation | `orientation` | | `vertical` |

**contentCards** — component `contentCards`
- content: `surface` ← `surface`
- styles: `color` ← `color`; `borderColor` ← `borderColor`, default `lightgrey`; `borderRadius` ←
  `borderRadius`, type number, default `9`; `backgroundColor` ← `backgroundColor`, default
  `white`

**list** — component `list`
- content: `sort` (default `none`), `items` ← `items`, `limit` (number, default `6`), `filter`
  (default `""`), `layout` (default `horizontal`), `listType` (default `screenList`),
  `imageWidth` (number, default `100`), `itemHeight` (number, default `100`), `itemsInRow`
  (number, default `1`), `imageResize` (default `contain`), `hideSubtitle` (boolean, default
  `false`), `itemRenderer` (default `stacked`), `filterProperty` (default `Category`),
  `titleFontWeight` (default `regular`), `subtitleFontWeight` (default `light`)
- styles: `gap` (number, default `8`), `color` (default `""`)

**map** — component `map` (no `mapCoordinates`)
- content: `text` ← `text`; `buttonLabel` ← `buttonLabel`; `showInModal` (boolean, default
  `false`); `buttonVariant` (default `contained`)
- styles: `color` (default `blue`), `locationColor` (default `green`)

**offer** — component `offer`
- content: `linkTo`, `opacity` (number, default `0.34`), `surface`, `showTitle` (boolean, default
  `true`), `imageResize` (default `cover`), `titleVariant` (default `titleLarge`),
  `fallbackImage`, `subtitleVariant` (default `titleMedium`), `contentAlignment` (default
  `center`), `showDebugMessage` (boolean, default `false`), `verticalAlignment` (default
  `bottom`), `horizontalAlignment` (default `center`)
- styles: `color` (default `#FFF`), `height` (number, default `200`), `borderRadius` (default
  `0`)

**productImage** — component `productImage`
- content: `fullWidth` (boolean, default `true`), `imageResize` (default `contain`)
- styles: `height` (number, default `250`)

**productTitle** — component `productTitle`
- content: `size` (default `large`), `weight` (default `bold`), `textAlignment` (default `left`)
- styles: `color` (default `#FFF`)

**timeSlotPicker** — component `timeSlotPicker`
- content: `path` ← `path`; `prefill` (boolean, default `true`); `timeOptions` ← `timeOptions`;
  `defaultValue` (default `""`); `numberOfDays` (number, default `3`); `numberOfTimeColumns`
  (number, default `3`)
- styles: `gap` (number, default `8`), `padding` (number, default `8`)

**userDetails** — component `userDetails`
- content: `showLogoutButton` (boolean, default `true`)

**userLogin** — component `userLogin`
- content: `title` (default `Login`), `passwordLabel` (default `Password`), `usernameLabel`
  (default `Email`)

## Testing

Mirror `ComponentMappingTest`'s existing pattern (author a block via `context.build().resource`,
export through the public `PageJsonExporter`, assert on the resulting component) — one test per
new mapping file confirming `component` name and a couple of representative fields, plus:
- `styles` is present and correctly typed on the four components that declare it.
- `datePicker` no longer emits input-only keys and does emit `type: "single"`.
- `screenKey()` uses node name (add/extend a `PageJsonExporterImplTest` case).
- `ComponentMappingTest`'s existing `index.json` drift check still passes with the 10 new
  entries (mirrors `FormSchemaServiceTest`'s index/directory parity check).

Run `mvn test -pl core` for the whole suite; no AEM instance needed for any of this (JCR_MOCK).

## Out of scope (recap)

- `map.mapCoordinates` (array of `{label, latitude, longitude}`) — needs a `children`
  field-rule operator the engine doesn't have yet.
- Our own extra component types (`action-button`, `product-card`, `insurance`,
  `loan-calculator`, `add-external`, `iframe`, `search`, `fileUpload`, `radioGroup`) — not
  removed or renamed to fit the reference schema's closed `component` enum; the exporter stays
  generic by design.
- `title`/`button`/`banner` count differences between the two apps — different content, not a
  defect.
- Any AEM authoring/dialog work to actually build these blocks in content — exporter-side only.
