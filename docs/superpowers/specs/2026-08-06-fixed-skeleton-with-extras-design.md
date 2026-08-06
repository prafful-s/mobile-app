# Fixed-skeleton mapping output with unconsumed-property passthrough — design

**Date:** 2026-08-06
**Branch:** feature/securfinancial-schema-rewrite (rollback: `rollback/pre-schema-rewrite`)
**Status:** approved

## Goal

The SecurFinancial reference contract defines a fixed JSON shape per component. The current
mapping engine emits only what a rule declares AND what was actually authored (or defaulted),
and drops the whole projection when nothing was authored. Both rules need to change for
components that opt in:

1. **Fixed skeleton** — every schema key always present, in declared order. Optional-and-
   unauthored keys emit as typed zero (`null` / `""` / `[]` / `false`) rather than being
   absent, so a client parses one shape.
2. **Unconsumed-property passthrough** — every CRX property this rule did not consume
   (i.e. not read by any `from` / `first[]` / `extract.from`), minus bookkeeping prefixes,
   is appended verbatim after the schema keys.

Both behaviors are per-rule opt-in (`"skeleton": true`) so classic non-Franklin content and
existing well-behaved rules stay on the old contract.

## Out of scope (deliberate)

- **Panel** (`ComponentTreeWalker.emitPanel`), **form** (`FormBlockBuilder`), and
  **form-fields** (`FormFieldFactory`) — these are built structurally, not from a JSON rule
  file. Aligning them with the same fixed-skeleton contract is a separate task.
- **`productDescription`** — reference schema is `{}`, generic Franklin fallback already
  produces exactly that. No rule needed.
- **Curated Sling models** (`BannerModel`, `CardModel`, `TitleModel`, `HtmlTextModel`,
  `ImageModel`, `DividerModel`) — these bypass the mapping engine when the resource type is
  `contentbridge/components/*`. Classic-authored content keeps their existing shapes.
  Franklin content (`model="hero"` etc.) still goes through the rules and gets the new
  behavior.
- **`map.mapCoordinates`** — still needs a `children` field-rule operator that does not exist
  yet. Flagged in the rule file, same as before.

## Design

### 1. `FieldRule` gets a `nullable` hint and exposes its consumed properties

```java
final class FieldRule {
    ...
    private final boolean nullable;   // NEW - emit as null (or []) when both source and default absent

    /** @return the CRX property names this rule reads from, so extras passthrough can skip them */
    List<String> consumedProperties() { ... }  // NEW - union of from + first + extract.from
}
```

`fromSource(...)` and `fallback()` unchanged. `ComponentMapping.apply()` is the caller that
consults `nullable` when both return absent.

### 2. `ComponentMapping` gets a per-rule `skeleton` flag

```json
{
  "match": ["hero", "banner"],
  "component": "banner",
  "skeleton": true,               // NEW
  "fields": [ ... ]
}
```

When `skeleton == true`:
- `apply()` emits every declared field always, in declared order, using `null`/`[]` for a
  `nullable` field that produced nothing. The current "empty projection is abandoned" rule
  is not applied.
- A companion method `consumedProperties()` returns the union of every field's
  `consumedProperties()`, exposed to the walker so it can compute extras.

When `skeleton == false` (or absent — backward-compatible default): current behavior. No
existing rule file changes.

### 3. `ComponentTreeWalker` appends unconsumed extras when the rule is in skeleton mode

Right after `content = mapping.apply(authored)`:

```java
if (mapping.isSkeleton()) {
    appendUnconsumedExtras(content, authored, mapping.consumedProperties());
}
```

`appendUnconsumedExtras` iterates the authored ValueMap. A property is included iff:
- Not in `consumedProperties()`.
- Not a bookkeeping key (same list `GenericComponent` already uses: prefixes `jcr:`, `sling:`,
  `cq:`, `mix:`, `nt:`, `granite:`, `oak:`, `rep:`; explicit `modelFields`, `model`,
  `filter`, `name`, `aueComponentId`).

Values pass through unchanged; `prefixAssetsDeep` runs afterwards as it does today, so DAM
references in extras get externalised automatically.

Extras are appended AFTER schema keys so the client sees a stable shape at the top.

### 4. `productDescription` needs no rule

Its reference shape is `{}`. When a Franklin block has `model="productDescription"` and no
other properties, `GenericComponent`'s generic path already produces `{ meta: { component:
"productDescription" }, content: {} }`. Adding a rule with `skeleton: true` would emit the
same empty content and gain nothing — verified by inspection.

## Rule file rewrites — 16 files

The following rules opt in with `"skeleton": true` and get their `fields` list rebuilt to
match the reference schemas. `nullable: true` is set on every optional-marked (`?`) schema
key; required keys keep their defaults.

| Rule file | Component | Notable |
|---|---|---|
| `title.json` | `title` | fields: `size` (default `medium`), `text` (extract from `title`/`jcr:title`/`text`), `weight` (default `regular`), `textColor` (nullable), `textAlignment` (nullable) |
| `button.json` | `button` | adds `icon`, `events`, `message`, `customEvent`, `customStyles` (all nullable) alongside existing `label`, `linkTo`, `variant`, `alignment`, `textColor` |
| `hero.json` | `banner` | keeps its extract-heavy field mapping; adds `nullable: true` on `subtitle`, `buttonLabel`, `linkTo`, `opacity` |
| `image.json` | `image` | adds `width`, `height`, `linkTo`, `imageSize`, `resizeMode`, `weight`, `justifyContent` (all nullable) |
| `card.json` | `card` | already comprehensive; sets `nullable: true` on `subtitle`, `buttonLabel`, `link`, `overlayBackground`, `backgroundColor` |
| `text.json` | `htmlText` | one field: `text` — required (extract remains) |
| `divider.json` | `divider` | adds `horizontalInset` (nullable) |
| `map.json` | `map` | `mapCoordinates` stays out (child-row aggregation gap), fields unchanged |
| `offer.json` | `offer` | `verticalAlignment` gains `middle` value support |
| `list.json` | `list` | `items` type expanded; `itemRenderer` supports `icon-item` |
| `contentCards.json` | `contentCards` | one field: `surface` |
| `productImage.json` | `productImage` | `fullWidth`, `imageResize` (nullable) |
| `productTitle.json` | `productTitle` | `size`, `weight`, `textAlignment` |
| `userLogin.json` | `userLogin` | `title`, `passwordLabel`, `usernameLabel` |
| `userDetails.json` | `userDetails` | `showLogoutButton` |
| `timeSlotPicker.json` | `timeSlotPicker` | as-is + nullable optional keys |

Every rewritten rule now emits its full schema shape even when the author sets nothing, plus
any extra properties the author did set that the rule did not consume.

## Test impact

- **`ComponentMappingTest#emptyProjectionIsAbandoned`** — obsolete under skeleton mode.
  Delete it. Its intent (a `hero` with only an unmapped field stays a `hero`) is now
  contradictory — with `skeleton: true` on `hero.json`, that block becomes a `banner`
  skeleton plus the unmapped field as an extra. That is the new contract.
- **`ComponentMappingTest#heroBecomesBanner`** — the `assertFalse(containsKey("text"))` and
  `assertFalse(containsKey("herolayout"))` assertions **still hold**. `text` is consumed by
  the extract for `title`/`subtitle`/`buttonLabel`/`linkTo`, and `herolayout` is consumed by
  the `layout` field. Both are in `consumedProperties()` → filtered from extras.
- **New per-rule tests** — for each rewritten rule, add a test that asserts the full
  schema key set is present (via `List.copyOf(content.keySet())` or `containsAll`) and that
  an unconsumed extra property authored on the source resource shows up in the output.

Full suite must pass after every task before moving to the next.

## Rollback

Tag `rollback/pre-schema-rewrite` at `b87fcbc` — the tip of the completed 12-task plan on
`feature/securfinancial-schema-match`. Branching off it keeps that work reachable
independently if this new work needs discarding wholesale.
