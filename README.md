# JEI Filter

A client-side addon for [Just Enough Items](https://github.com/mezz/JustEnoughItems) on **Minecraft 1.20.1 / Forge**.
It adds a **hopper button to the left edge of JEI's search bar**. Clicking it opens a menu where you tick
mods to hide their items and recipes from JEI, with both **positive selection (show only checked)** and
**negative selection (hide checked)**.

## What it looks like

```
┌────────────────────────────────────────────────┐
│ JEI ingredient list                            │
│  ...grid of items...                           │
│ ┌──┐┌──────────────────────────────┐┌──┐       │
│ │🔻││ Search Items                 ││⚙│       │
│ └──┘└──────────────────────────────┘└──┘       │
│  ▲                                             │
│  hopper button added by this mod               │
└────────────────────────────────────────────────┘
```

The hopper is tinted **green** whenever a filter is active, so you can tell at a glance that JEI is
hiding something. Hovering it shows the tooltip with the number of hidden mods and items.

## The filter menu

| Control | What it does |
|---|---|
| **Mode: Hide checked** (blacklist) | Checked mods are hidden from JEI. Default; nothing is hidden until you tick something. |
| **Mode: Show only checked** (whitelist) | Everything is hidden *except* the checked mods. |
| **Sort: Name / Items / Mod ID** | Reorders the list. "Items" puts the mods with the most items first, which is the fastest way to find a big mod. |
| **Search box** | Filters the list by mod name or mod id. |
| **All / None / Invert** | Bulk-select the mods currently listed by the search box. Invert is the reverse selection: everything listed flips. |
| **Checkbox, or click the row** | Toggles one mod. Changes apply to JEI immediately, so you can watch items disappear while the screen is open. |
| **Keyboard** | Up/Down move the cursor, Space/Enter toggle, Page Up/Down scroll, Escape closes. |

Every change is written to `config/jei_filter.json` immediately, so the filter survives a restart.

```json
{
  "_comment": "Mods checked in JEI's filter button menu, and what checked means.",
  "options": {
    "mode": "blacklist",
    "selected": ["mekanism", "thermal"]
  }
}
```

Delete that file (or untick everything) to get back to a stock JEI.

## How it works

- **Every ingredient type is filtered, not just items.** JEI's list is made of separate ingredient
  types (items, Forge fluids, and anything another mod registers). `ModCatalog` walks
  `IIngredientManager#getRegisteredIngredientTypes()`, reads `getAllIngredients` for each, and
  attributes every ingredient to a mod through the namespace of its
  `IIngredientHelper#getResourceLocation`. That is what makes "hide this mod" cover its fluids and
  custom ingredients too, and it is why a whitelist that keeps only `minecraft` leaves no fluid
  behind.
- **The mod list** is the aggregation of that catalog: one row per mod, with the count of everything
  that mod contributes across all ingredient types, and the display name from JEI's `IModIdHelper`.
- **Hiding goes through `JeiVisibilityBridge`**, which picks the API the running JEI actually has.
  JEI only grew `IIngredientVisibility#hideIngredients`/`#unhideIngredients` (with
  `UidContext.Ingredient` + `UidContext.Recipe`) in **15.55.0**; before that the public route is
  `IIngredientManager#removeIngredientsAtRuntime`/`#addIngredientsAtRuntime`, and inside JEI both end
  up in the same `IngredientBlacklistInternal`. The newer call is reached by reflection so the mod
  still loads on older JEI, and `gradle.properties` pins the compile target to the oldest supported
  version for exactly that reason. On 15.55.0+ hiding additionally covers recipe slots and catalysts.
- **The button** is placed by one Mixin into JEI's `IngredientListOverlay` (see
  `com.jeifilter.mixin.IngredientListOverlayMixin`). JEI builds one "search field + config button"
  strip in `updateBounds`; the mixin shrinks that strip by 20px on the left, keeps the 20px for
  itself, and lets JEI lay out the rest exactly as before. It then draws its button with the search
  bar. The icon is `IGuiHelper#createDrawableItemLike(Items.HOPPER)`, i.e. the vanilla hopper.
- **Clicks** are picked up by `JeiFilterInputEvents` through Forge's `ScreenEvent.MouseButtonPressed`
  / `MouseButtonReleased` at `HIGHEST` priority, which fires before vanilla `Screen#mouseClicked` and
  before JEI's own listener. This is the only click path on purpose: JEI's own input-handler classes
  moved packages and changed contract between 15.20 and 15.55, so hooking them would not work across
  versions, and the screen-level hook does not need them.
- **The "filter is active" badge is a drawn dot**, not a tint. JEI renders the icon through the item
  render path, which does not honour `RenderSystem.setShaderColor`, so a tinted hopper measures
  identical to an untinted one. The dot is painted in the button's bottom-right corner, and the
  tooltip switches to `Filtering N mods (M items hidden)`.
- **A partial ingredient list is never applied.** Ingredient lookups do not have a "finished
  loading" signal, so `rebuildCatalog` refuses a catalog that is missing mods the previous one had.
  This matters in whitelist mode, where "not in the catalog" would otherwise mean "hidden" and mods
  could flicker out and back in while JEI is still loading.
- **`applyNow` is re-entrancy guarded.** `removeIngredientsAtRuntime`/`addIngredientsAtRuntime`
  notify ingredient listeners synchronously, and this mod is one of them, so without the guard
  `applyNow → addIngredientsAtRuntime → onIngredientsAdded → refreshFromIngredients → applyNow`
  recurses until the stack overflows. Nothing this mod tells JEI is news about JEI's ingredient list.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x (`47.4.23` built against) |
| JEI | **15.20.0 or newer** (`15.20.0.129` built against, so the mod also loads on the many 1.20.1 packs still pinned to an older JEI) |
| Side | **Client only.** It is a GUI mod; installing it on a server does nothing and is not required. |

On JEI **15.55.0+** hiding also covers recipe slots and catalysts (`UidContext.Recipe`). On older
JEI it covers the ingredient list, which is what the filter menu is about.

## Building

```
gradlew.bat build
```

The jar lands in `build/libs/jei_filter-1.0.0+mc1.20.1.jar`.

The build needs a **JDK 17**. `gradle.properties` currently pins one with an absolute
`org.gradle.java.home` path, because on the machine this was built on the JDK lives in a `tools/`
directory outside the repository (which is gitignored, so it is never committed). **On any other
machine that line must be changed or removed** — with it removed, Gradle uses `JAVA_HOME` (or the
`java` on `PATH`), so a JDK 17 there is enough. Gradle itself is 8.14.5 via the wrapper, which is the
last line ForgeGradle 6 runs cleanly on.

Useful tasks:

```
gradlew.bat build          # compile + mixin annotation processing + jar
gradlew.bat unitTest       # JUnit tests for the filter model (20 tests)
gradlew.bat runClient      # dev client with JEI loaded
gradlew.bat runServer      # dev server; JEI Filter is client-only and does nothing here
```

Two build details worth knowing before you touch `build.gradle`:

- **`gradlew test` is disabled on purpose** and pinned to `unitTest`. ForgeGradle makes the stock
  `test` task's classpath extend Forge's patched Minecraft, which is not a usable JUnit runtime, so
  the tests live in their own `src/unitTest` source set and run through the JUnit console launcher.
  `gradlew build` runs them via `check`.
- **JEI must be wrapped in `fg.deobf` on the runtime side.** JEI's published jar is SRG-named: its own
  members keep their mojmap names, but the calls it makes into Minecraft use SRG names
  (`Minecraft.m_91087_()`). The dev jar is official-named (`Minecraft.getInstance()`), so a raw
  `runtimeOnly` dependency makes `runClient` die while JEI is starting with
  `NoSuchMethodError: net.minecraft.client.Minecraft.m_91087_()` — while `gradlew build` still
  succeeds, because the jar task never resolves `runtimeClasspath`.
- **The BlameJared repository must not be narrowed with a `content { }` filter.** JEI's own
  transitive dependencies (`net.mezzdev.config:mezz_config-*`, `net.mezzdev:deduplicating-runner`)
  live there and are not on Maven Central. A filter that only allows `mezz.jei` still compiles, but
  makes `runClient` fail to resolve `runtimeClasspath`.

## Verifying it works

Because the mixin config is `required: true` with `defaultRequire: 1`, a JEI change that breaks an
injection point shows up as a crash at startup rather than a silently missing button. On a healthy
start `logs/latest.log` contains:

```
JEI Filter is loading (client side = true)
...
jei_filter: hopper filter button created for the JEI search bar
jei_filter: JEI exposes <n> ingredients from <m> mods
```

and `logs/debug.log` contains one `Mixing ...` line per mixin:

```
Mixing IngredientListOverlayMixin from jei_filter.mixins.json into mezz.jei.gui.overlay.IngredientListOverlay
Mixing GuiTextFieldFilterAccessor from jei_filter.mixins.json into mezz.jei.gui.input.GuiTextFieldFilter
```

Two more lines confirm the filter itself reached JEI. Which pair you see depends on the JEI version,
because `JeiVisibilityBridge` picks the API that exists:

```
jei_filter: hopper pressed at (x, y); opening the mod filter screen            (debug level)

JEI 15.55.0+:
  Ingredients are being hidden at runtime in [Ingredient, Recipe]: <n> net.minecraft.world.item.ItemStack

older JEI:
  jei_filter: this JEI has no hideIngredients/unhideIngredients (added in JEI 15.55.0); falling back
  Ingredients are being removed at runtime: <n> net.minecraft.world.item.ItemStack
```

### Verification status

Verified in a live dev client on Forge 47.4.23, by driving the game and reading the logs it wrote.

On **JEI 15.62.0.217** (the `hideIngredients` path):

- JEI loads; both mixins apply to the real classes; the hopper renders at the left of JEI's search
  bar with a working tooltip.
- Clicking the hopper opens the menu.
- In the default "hide checked" mode, ticking `Minecraft` makes JEI hide all 1559 of its item
  ingredients and the overlay's grid empties; unticking brings them back. JEI's own log confirms the
  direction: `Ingredients are being hidden ...` on the tick and `Ingredients are being unhidden ...`
  on the untick.
- The "filter is active" dot is visible (measured `max(G−R)` of 6 inactive vs 170 active in the
  button's corner) and the tooltip switches to `Filtering N mods (M items hidden)`.
- The selection persists to `config/jei_filter.json` and is read back on the next start.

On **JEI 15.20.0.129** (the `removeIngredientsAtRuntime` path, i.e. an older pack):

- Both mixins still apply; JEI loads the mod; the button is created; the catalog is read once.
- The bridge logs that it fell back, and a filter preselected in `config/jei_filter.json` produces
  `Ingredients are being removed at runtime: N net.minecraft.world.item.ItemStack` **and**
  `... 2 net.minecraftforge.fluids.FluidStack` — i.e. every ingredient type is covered, which is the
  fix for fluids and other non-item ingredients staying visible.
- No recursion and no crash. This path used to recurse until `StackOverflowError` because
  `addIngredientsAtRuntime` re-enters this mod's own ingredient listener; that is what the
  `applyingVisibility` guard in `applyNow`/`refreshFromIngredients` fixes.

Not yet verified on 15.20: opening the menu by clicking, and that the grid visually empties (the
dev instance for that check loads a single mod, so it was exercised on 15.62 instead).

Known gaps:

- A mod is only filterable if JEI reports a resource location for its ingredients. An ingredient
  built by JEI itself, with no mod namespace, is attributed to `jei` rather than dropped.
- Whitelist mode with nothing ticked hides everything including vanilla. There is no
  "you are about to hide everything" confirmation.
- On JEI older than 15.55.0, hiding affects the ingredient list but not recipe slots/catalysts.
- On that same older JEI, applying a filter makes JEI log a remove/add pair per ingredient type per
  application, because `removeIngredientsAtRuntime`/`addIngredientsAtRuntime` is the only public
  route there.

## Project layout

```
src/main/java/com/jeifilter/
  JeiFilterMod.java              mod entry point, config directory
  jei/JeiFilterPlugin.java       IModPlugin: JEI runtime available / unavailable
  jei/JeiFilterService.java      filter state, mod catalog, hide/unhide driver, persistence
  jei/JeiVisibilityBridge.java   picks hideIngredients (15.55+) or removeIngredientsAtRuntime
  filter/FilterOptions.java      immutable selection + mode, JSON round trip, hide/unhide plan
  filter/FilterMode.java         blacklist / whitelist
  filter/ModEntry.java           one row of the menu
  client/JeiFilterButton.java    the hopper button
  client/JeiFilterInputEvents.java  screen-level click hook for the hopper
  client/gui/ModFilterScreen.java the menu
  mixin/IngredientListOverlayMixin.java   search bar layout, draw, tooltip
  mixin/GuiTextFieldFilterAccessor.java   reads JEI's search field bounds
src/unitTest/java/com/jeifilter/filter/   JUnit tests for the filter model
src/main/resources/
  META-INF/mods.toml             mod metadata
  jei_filter.mixins.json         mixin config
  assets/jei_filter/lang/        en_us + zh_cn
```

## Limitations

- Only **item** ingredients are filtered. Fluid and other ingredient types registered by other mods
  are not part of this filter; a hidden mod's items simply stop appearing in recipe slots.
- The ingredient list is a snapshot taken when JEI becomes available and refreshed when JEI reports
  ingredient changes, so a mod that adds items at runtime appears in the menu after that refresh.
- Hiding `jei` itself is allowed and hides JEI's own items (its lookup history etc. is unaffected).

## License

MIT.
