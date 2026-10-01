# JEI Filter

A client-side addon for [Just Enough Items](https://github.com/mezz/JustEnoughItems) on **Minecraft 1.20.1 / Forge**.
It adds a **hopper button to the left edge of JEI's search bar**. Clicking it opens a menu where you tick
mods to hide everything they register from JEI, with both **positive selection (show only checked)** and
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

While a filter is active a **green dot** is drawn in the button's bottom-right corner, so you can tell
at a glance that JEI is hiding something. Hovering it shows the tooltip with the number of hidden mods
and ingredients.

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
  attributes every ingredient to a mod. That is what makes "hide this mod" cover its fluids and
  custom ingredients too, and it is why a whitelist that keeps only `minecraft` leaves no fluid
  behind.
- **Attribution follows JEI, not the registry name.** `ModAttribution` asks for the ingredient's
  *creator* mod id first (JEI's `IIngredientHelper#getDisplayModId`, which for an `ItemStack` is
  Forge's `Item#getCreatorModId`) and only falls back to the namespace of the registry name. This
  matters more than it looks: a modded potion is still registered as `minecraft:potion` and a modded
  enchanted book as `minecraft:enchanted_book`, so a namespace-only rule files both under
  `minecraft` and "hide minecraft" leaves exactly those items on screen. `ModAttributionTest` pins
  the precedence.
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
- **On JEI older than 15.55.0 the catalog is never re-read while a filter is active.** That route
  hides by taking ingredients *out* of JEI's list, so re-reading it would return the filtered list,
  conclude the hidden mods are simply absent, and unhide them again — the "the items came back after
  a moment" bug. There, an ingredient change only marks the catalog stale, and it is re-read at a
  deliberate moment (opening the menu, or changing the selection) through `refreshCatalogNow`, which
  un-hides first so the list is complete while it is read, and refuses a catalog that shrank. On JEI
  15.55.0+ hiding is a visibility flag, `getAllIngredients` still returns everything, and the catalog
  can simply be rebuilt.
- **Visibility changes and catalog re-reads are mutually exclusive.** `refreshCatalogNow` holds the
  same guard as `applyNow` for its whole "un-hide, re-read, re-hide" sequence. Without that, a menu
  open could rebuild the catalog from a half-hidden list and the hidden set would be recomputed from
  whatever was left, which is what made items reappear and then disappear again.
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
gradlew.bat build          # compile + mixin annotation processing + jar + tests
gradlew.bat unitTest       # JUnit tests only (37 tests)
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
jei_filter: JEI exposes <ingredients> ingredients from <mods> mods across <types> ingredient type(s)
jei_filter: hiding <mods> mods (<ingredients> ingredients) from JEI
```

and `logs/debug.log` contains one `Mixing ...` line per mixin:

```
Mixing IngredientListOverlayMixin from jei_filter.mixins.json into mezz.jei.gui.overlay.IngredientListOverlay
Mixing GuiTextFieldFilterAccessor from jei_filter.mixins.json into mezz.jei.gui.input.GuiTextFieldFilter
```

When you tick something, JEI itself confirms which API was used — the pair you see depends on the
JEI version, because `JeiVisibilityBridge` picks the one that exists:

```
JEI 15.55.0+:
  Ingredients are being hidden at runtime in [Ingredient, Recipe]: <n> net.minecraft.world.item.ItemStack

JEI older than 15.55.0:
  jei_filter: this JEI has no hideIngredients/unhideIngredients (added in JEI 15.55.0); falling back
  Ingredients are being removed at runtime: <n> net.minecraft.world.item.ItemStack
  Ingredients are being removed at runtime: <n> net.minecraftforge.fluids.FluidStack
```

One line per ingredient type is expected: hiding covers items, fluids and any other registered type.

### Verification status

Verified by launching the dev client on Forge 47.4.23 and driving the game, reading the logs it wrote.

On **JEI 15.62.0.217**:

- Both mixins apply to the real classes; the hopper renders at the left of JEI's search bar with a
  working tooltip; clicking it opens the menu.
- In the default "hide checked" mode, ticking `Minecraft` hides all 1559 of its item ingredients and
  the overlay's grid empties; unticking brings them back. JEI's own log confirms the direction
  (`hidden` on the tick, `unhidden` on the untick).
- The "filter is active" dot is visible (measured `max(G−R)` of 6 inactive vs 170 active in the
  button's corner) and the tooltip switches to `Filtering N mods (M items hidden)`.
- The selection persists to `config/jei_filter.json` and is read back on the next start.

On **JEI 15.20.0.129** (an older pack — the `removeIngredientsAtRuntime` route):

- Both mixins still apply, the mod loads, the button is created, and the catalog is read once.
- Hiding covers **every ingredient type**: a filter produces
  `Ingredients are being removed at runtime: 1556 ... ItemStack` **and**
  `... 2 ... FluidStack`.
- Items stay hidden. Verified by asking JEI what it is currently showing after a hide:
  `catalogHas=1556` against `jeiShows=0`, then `jeiShows=1556` again after unticking.
- No recursion, no crash, and nothing is added back seconds later.

Reported working by the user in a **324-mod pack** on JEI 15.20.0.129, whitelist mode with only
`minecraft` ticked: modded potions, potion arrows, enchanted books and fluids are all gone.

### Not covered by the checks above

- The **JEI 15.55.0+ hiding path has not been exercised since the attribution fix**. It is the same
  decision code feeding a different call, and it worked before that change, but the exact build
  shipped here was only run end to end on JEI 15.20. If you are on 15.55+, the equivalent check is
  that ticking a mod logs `Ingredients are being hidden at runtime in [Ingredient, Recipe]` for each
  ingredient type, and that unticking logs the `unhidden` counterpart.
- Recipe-slot and catalyst hiding (`UidContext.Recipe`) only exists on 15.55.0+; on older JEI the
  ingredient list is what is filtered.

### Known gaps

- A mod is only filterable if JEI can attribute its ingredients to it. An ingredient that reports
  neither a creator mod id nor a registry namespace is skipped entirely; one that reports only
  `minecraft` (a vanilla item) stays with `minecraft`.
- Whitelist mode with nothing ticked hides everything, including vanilla. There is no "you are about
  to hide everything" confirmation.
- On JEI older than 15.55.0, applying a filter makes JEI log a remove/add pair per ingredient type
  per application, because `removeIngredientsAtRuntime`/`addIngredientsAtRuntime` is the only public
  route there.

## Project layout

```
src/main/java/com/jeifilter/
  JeiFilterMod.java              mod entry point; registers the screen-level click hook
  jei/JeiFilterPlugin.java       IModPlugin: JEI runtime available / unavailable
  jei/JeiFilterService.java      filter state, catalog, hide/unhide driver, persistence
  jei/ModCatalog.java            every ingredient of every type, grouped by mod
  jei/JeiVisibilityBridge.java   picks hideIngredients (15.55+) or removeIngredientsAtRuntime
  filter/ModAttribution.java     creator-mod-id first, registry namespace as the fallback
  filter/FilterOptions.java      immutable selection + mode, JSON round trip, hide/unhide plan
  filter/FilterMode.java         blacklist / whitelist
  filter/ModEntry.java           one row of the menu, and its sort orders
  client/JeiFilterButton.java    the hopper button and its "filter is active" dot
  client/JeiFilterInputEvents.java  screen-level click hook for the hopper
  client/gui/ModFilterScreen.java   the menu
  mixin/IngredientListOverlayMixin.java   search bar layout, draw, tooltip
  mixin/GuiTextFieldFilterAccessor.java   reads JEI's search field bounds
src/main/resources/
  META-INF/mods.toml             mod metadata
  jei_filter.mixins.json         mixin config
  assets/jei_filter/lang/        en_us + zh_cn
  jei_filter.png                 mod logo
src/unitTest/java/com/jeifilter/filter/
  FilterOptionsTest.java         selection, mode, JSON round trip
  FilterPlanTest.java            which mods get hidden vs unhidden
  ModAttributionTest.java        creator mod id vs registry namespace
  ModEntryTest.java              search matching and sort orders
```

## Limitations

- **Client only.** Nothing happens on a dedicated server; the mod is not needed there.
- Hiding is by **mod**, not by individual ingredient. To hide a single item, use JEI's own
  hide-item feature.
- A mod only appears in the menu once JEI is reporting ingredients for it, which happens when a world
  is loaded. The menu is empty on the title screen.
- Hiding `jei` itself is allowed; it hides JEI's own items. JEI's lookup history is unaffected.
- Changing the selection makes JEI remove or re-add ingredients, so on older JEI a large filter
  change is visible for a moment as the list re-populates.

## Development notes

Five defects in this mod were only findable in a large pack, not in the single-mod dev instance.
They are recorded here because each one is a trap worth recognising again:

| Symptom | Cause |
|---|---|
| Modded potions, potion arrows, enchanted books survive "hide minecraft" | Attribution used the registry namespace. A modded potion is `minecraft:potion`; only the creator-mod-id lookup names the mod that added it. |
| Fluids survive "hide everything" | Only `VanillaTypes.ITEM_STACK` was filtered. JEI's fluid ingredients are a separate registered type. |
| `StackOverflowError` on an older JEI | `removeIngredientsAtRuntime`/`addIngredientsAtRuntime` notify ingredient listeners synchronously, and this mod is one. |
| Hidden items reappear seconds later on an older JEI | The catalog was re-read while the filter was applied, so it returned the *filtered* list and the hidden mods looked absent. |
| Items reappear, then disappear again | A menu open rebuilt the catalog in the middle of a hide, so the hidden set was recomputed from a half-applied state. |

Two lessons that generalise:

- **A hide that "ran" is not a hide that "took".** On the older JEI route the ingredient list is the
  thing being modified, so reading it back after hiding returns the filtered list. The useful
  assertion in a live client is `IIngredientManager#getAllItemStacks().size()` after a hide, not
  whether a hide call was issued.
- **Attribution errors are invisible in small dev instances** and obvious in a large pack, because
  they only show up for ingredients whose registry name disagrees with the mod that added them.

## License

MIT.
