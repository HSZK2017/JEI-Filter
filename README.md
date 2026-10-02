# JEI Filter

A client-side addon for [Just Enough Items](https://github.com/mezz/JustEnoughItems) on **Minecraft 1.20.1 / Forge**.
It adds a **hopper button to the left edge of JEI's search bar**. Clicking it opens a menu where you
tick what JEI should show, on two independent axes: **by mod**, and by **what kind of thing it is**
(potions, arrows, enchanted books). Everything starts ticked, so a fresh install changes nothing, and
you hide things by unticking them.

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
at a glance that JEI is hiding something. Hovering it shows the tooltip with the number of hidden
ingredients.

## The filter menu

The list has two levels. At the top, one row per **category**; below it, one row per **mod**, each of
which expands into one sub-row per category that mod actually has.

```
[x] Potions            12 mods, 340
[x] Arrows              4 mods,  61
[x] Enchanted Books     8 mods, 190
──────────────────────────────────
> [x] Create                482
v [~] Mekanism              912
      [x] Potions           96
      [ ] Arrows            12
      [x] Other items      804
> [x] Minecraft            1558
```

**A ticked box means "show it".** Nothing is hidden until you untick something, and every box reads
that way wherever it appears — there is no mode in which a tick means "hide", because a box that flips
its meaning is how the two levels get out of step.

| Control | What it does |
|---|---|
| **Category row** | A master switch over that category for **every** mod. Ticking or unticking it ticks or unticks each mod's sub-row for that category at once. |
| **Mod row (`>` / `v`)** | The arrow opens the mod's category sub-rows. Clicking the row itself ticks the whole mod, which shows everything it has. |
| **Mod sub-row** | One category of one mod. Untick it to hide just that — everyone else's potions stay, and so does the rest of this mod. |
| **Search box** | Filters by mod name, mod id, or category name. Matches open automatically so you can see why they matched. |
| **Sort: Name / Items / Mod ID** | Reorders the mod list. "Items" puts the biggest mods first, which is the fastest way to find one. |
| **All / None / Invert** | Bulk-select everything currently listed. Invert flips it. |
| **Keyboard** | Up/Down move, Space/Enter toggle, Left/Right collapse/expand a mod, Page Up/Down scroll, Escape closes. |

### The three-state boxes

A checkbox reads as one of three things, and both levels work the same way:

| Box | Meaning |
|---|---|
| ☐ empty | nothing under this row is shown |
| ▣ square | **some** of what is under this row is shown — e.g. some mods' potions are on and others are not |
| ☑ tick | **all** of what is under this row is shown |

The category row is a pure aggregate of the sub-rows beneath it — it is not a separate setting. That
one rule is what makes it able to drive them: tick it and every mod's sub-row for that category ticks;
untick it and they all clear; tick one mod's sub-row afterwards and the category row drops to the
square "mixed" state.

### How the two levels combine

An item is shown when **either** its mod's box is ticked **or** the box for its category under that mod
is:

```
shown(mod, category) = (ticked(mod) and not unticked(mod|category)) or ticked(mod|category)
```

So ticking a mod shows everything it has, and unticking one category of a ticked mod hides just that
category while the rest stays. The `unticked(mod|category)` term is what makes that last case work: the
mod's tick has to keep showing its *other* categories, so turning one off is recorded as an exception
rather than by clearing the mod's tick. `unticked` is the second of the two lists in the saved config.

### A fresh install shows everything

Every mod is ticked the first time JEI reports it, so JEI behaves normally until you untick something.
A mod that loads later is ticked too, rather than appearing already hidden.

Every change is written to `config/jei_filter.json` immediately, so the filter survives a restart.

```json
{
  "options": {
    "ticked": ["mekanism", "thermal|potion", "create|enchanted_book"],
    "unticked": ["mekanism|arrow"]
  }
}
```

A facet is either a whole mod (`"mekanism"`) or one category of one mod (`"thermal|potion"`). The
category ids are `potion`, `arrow`, `enchanted_book` and `main`; `main` is the fallback bucket holding
everything else, including fluids.

`unticked` holds individual categories turned off while their mod stays ticked, and is omitted when
empty. In the example above mekanism is shown as a whole except its arrows, thermal shows only its
potions, and create shows only its enchanted books.

A config written by an older version of this mod still loads: `ticked`, `tickedMods` and `selected`
are all read, and all of them held whole-mod keys, which are still whole-mod facets.

## How it works

- **Every ingredient type is filtered, not just items.** JEI's list is made of separate ingredient
  types (items, Forge fluids, and anything another mod registers). `ModCatalog` walks
  `IIngredientManager#getRegisteredIngredientTypes()`, reads `getAllIngredients` for each, and
  attributes every ingredient to a mod. That is what makes "hide this mod" cover its fluids and
  custom ingredients too, and it is why keeping only `minecraft` ticked leaves no fluid behind.
- **Attribution follows JEI, not the registry name.** `ModAttribution` asks for the ingredient's
  *creator* mod id first (JEI's `IIngredientHelper#getDisplayModId`, which for an `ItemStack` is
  Forge's `Item#getCreatorModId`) and only falls back to the namespace of the registry name. This
  matters more than it looks: a modded potion is still registered as `minecraft:potion` and a modded
  enchanted book as `minecraft:enchanted_book`, so a namespace-only rule files both under
  `minecraft` and "hide minecraft" leaves exactly those items on screen. `ModAttributionTest` pins
  the precedence.
- **The mod list** is the aggregation of that catalog: one row per mod, with the count of everything
  that mod contributes across all ingredient types, and the display name from JEI's `IModIdHelper`.
- **Categories are decided by class, and only by class.** `IngredientCategory` asks one question: is
  the item an instance of `PotionItem`, `ArrowItem` or `EnchantedBookItem`? Nothing else is consulted —
  not the registry name, not the translation key, not the tooltip.

  That covers vanilla for free, because every potion in the game *is* a `PotionItem`, and it covers
  mods because mods subclass the base. Checked with `javap` against this pack:

  | Mod | Class | Extends |
  |---|---|---|
  | soulslike-weaponry | `CustomPotionItem` | `PotionItem` |
  | soulslike-weaponry | `CustomSplashPotion` | `CustomPotionItem` |
  | iceandfire | `ItemDragonArrow` | `ArrowItem` |
  | alexsmobs | `ItemModArrow` | `ArrowItem` |
  | alexscaves | `BurrowingArrowItem` | `ArrowItem` |
  | goety | `BrewArrowItem` | `ArrowItem` |

  **There is deliberately no name matching.** An earlier version also matched the registry name, to
  catch items that behave like one of these without subclassing the base. That was removed: a name is
  a guess, and in a real pack the guesses are wrong often enough to matter. Items whose names contain
  "potion" or "arrow" but which are not potions or arrows exist in quantity — a gun magazine, a quiver,
  an arrowhead, decorative potion blocks and bottles that are never drunk. Hiding a category hides
  everything in it, so every false positive silently removes something the player never chose to hide.

  The cost is real and accepted: something that behaves like a potion but extends plain `Item` — goety's
  `UndeathPotionItem` is one — is **not** a potion here and stays in `main`, so it is never swept into
  the potion category. That is the safe direction. An item that stays visible is a mild annoyance; an
  item that vanishes without being asked is a bug.
- **Hiding is per ingredient, not per mod.** The plan is a set of ingredients, because that is the only
  unit that can express "every potion in the pack, but nothing else". `ModCatalog` indexes every
  ingredient by its registry name plus JEI's unique id (so two enchanted books with different
  enchantments stay distinct) and keeps the reverse map, so applying a change does not rescan tens of
  thousands of ingredients on every checkbox click.
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
  tooltip switches to `N ingredients hidden`.
- **A mod first seen later is ticked automatically.** `rebuildCatalog` notices mods that no earlier
  catalog had and ticks their facets before applying. Without that, a mod that loads after the saved
  config was written would be hidden the instant it appeared, because an unticked
  facet is a hidden one.
- **A partial ingredient list is never applied.** Ingredient lookups do not have a "finished
  loading" signal, so `rebuildCatalog` refuses a catalog that is missing mods the previous one had.
  This matters because "not in the catalog" would otherwise mean "hidden" and mods
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
gradlew.bat unitTest       # JUnit tests only (60 tests)
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
jei_filter: hiding <ingredients> ingredients from JEI
jei_filter: category potion: <n> ingredients across <m> mods
jei_filter: category arrow: <n> ingredients across <m> mods
jei_filter: category enchanted_book: <n> ingredients across <m> mods
jei_filter: <n> ingredients are in no special category
```

The `category` lines are the category rules reporting what they found. A category that finds nothing is
omitted, and one whose count looks wrong means the class checks are not catching what they should —
which is worth checking, because hiding a category hides everything in it.

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
- Unticking `Minecraft` hides all 1559 of its item ingredients and the overlay's grid empties;
  re-ticking brings them back. JEI's own log confirms the direction (`hidden` on the untick,
  `unhidden` on the re-tick).
- The "filter is active" dot is visible (measured `max(G−R)` of 6 inactive vs 170 active in the
  button's corner) and the tooltip switches to `N ingredients hidden`.
- The selection persists to `config/jei_filter.json` and is read back on the next start.

**Note:** the checkbox model was rewritten after those runs (the mode was removed and the
category-exception set added), so the bullet above about how the boxes read describes the current
build only in shape — the numbers and the persistence were measured on it, the exact click-through was
not.

On **JEI 15.20.0.129** (an older pack — the `removeIngredientsAtRuntime` route), current build:

- Both mixins still apply, the mod loads, the button is created, and the catalog is read once.
- **A fresh install hides nothing.** With no config file the mod writes `{"ticked":["<modid>"]}` and
  logs `hiding 0 ingredients`, so JEI behaves as if the mod were not installed until something is
  unticked.
- **Categories classify real ingredients.** Measured in the dev client:
  ```
  category potion: 126 ingredients across 1 mods
  category arrow: 44 ingredients across 1 mods
  category enchanted_book: 113 ingredients across 1 mods
  1275 ingredients are in no special category
  ```
  126 + 44 + 113 + 1275 = 1558, the same total the catalog reports, so every ingredient is accounted
  for exactly once.
- **Unticking a category hides exactly that category, and nothing else.** Driving every box in turn
  produced hides of exactly `126` (potions), `44` (arrows), `113` (enchanted books), `1275` (the
  ordinary items) and `1558` (the whole mod), each returning to `0` when re-ticked. Nothing was over-
  or under-hidden, which is what says the two levels are composing correctly.
- **The selection persists at facet granularity.** After driving those toggles the saved config was
  `{"ticked":["minecraft|main"]}` — a single mod-plus-category facet, which the next start read back.
- Hiding covers **every ingredient type**: `... removed at runtime: N ... ItemStack` **and**
  `... N ... FluidStack`.
- Items stay hidden. Verified by asking JEI what it is currently showing after a hide:
  `catalogHas=1556` against `jeiShows=0`, then `jeiShows=1556` again after re-ticking.
- No recursion, no crash, and nothing is added back seconds later.

Reported working by the user in a **324-mod pack** on JEI 15.20.0.129: modded potions, potion arrows,
enchanted books and fluids can all be hidden.

### Not covered by the checks above

- **The two-level menu has not been driven through the GUI in a large pack.** The category model, the
  three-state boxes and the master/sub-box syncing are covered by 60 unit tests, and the end-to-end
  category hiding above was verified in a real client — but that client has one mod, so "some mods'
  potions on and others off" was only exercised in tests, never on screen.
- **The checkbox model was rewritten late**, after the first round of user testing, so the menu
  click-through itself has not been re-confirmed on screen since. What was measured on this build is
  the filtering behaviour and the persistence, not the clicking.
- **The JEI 15.55.0+ hiding path has not been re-run since the category change.** It is the same
  decision code feeding a different call, but the build shipped here was only run end to end on JEI
  15.20. If you are on 15.55+, the equivalent check is that ticking a category logs
  `Ingredients are being hidden at runtime in [Ingredient, Recipe]` and that unticking logs the
  `unhidden` counterpart.
- Recipe-slot and catalyst hiding (`UidContext.Recipe`) only exists on 15.55.0+; on older JEI the
  ingredient list is what is filtered.

### Known gaps

- A mod is only filterable if JEI can attribute its ingredients to it. An ingredient that reports
  neither a creator mod id nor a registry namespace is skipped entirely; one that reports only
  `minecraft` (a vanilla item) stays with `minecraft`.
- With every mod unticked, nothing is shown at all. There is no "you are about
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
  jei/ModCatalog.java            every ingredient, grouped by mod and category, with both indexes
  jei/JeiVisibilityBridge.java   picks hideIngredients (15.55+) or removeIngredientsAtRuntime
  filter/IngredientCategory.java potion / arrow / enchanted_book, by item class then by name
  filter/ModAttribution.java     creator-mod-id first, registry namespace as the fallback
  filter/FilterOptions.java      the two-level selection, three-state reads, hide/unhide plan, JSON
  filter/ModEntry.java           one row of the menu, and its sort orders
  client/JeiFilterButton.java    the hopper button and its "filter is active" dot
  client/JeiFilterInputEvents.java  screen-level click hook for the hopper
  client/gui/ModFilterScreen.java   the menu: category rows over expandable mod rows
  mixin/IngredientListOverlayMixin.java   search bar layout, draw, tooltip
  mixin/GuiTextFieldFilterAccessor.java   reads JEI's search field bounds
src/main/resources/
  META-INF/mods.toml             mod metadata
  jei_filter.mixins.json         mixin config
  assets/jei_filter/lang/        en_us + zh_cn
  jei_filter.png                 mod logo
src/unitTest/java/com/jeifilter/filter/
  FilterOptionsTest.java         both axes, three-state reads, master/sub syncing, plan, JSON
  IngredientCategoryTest.java    the category contract (classification itself is checked with javap)
  ModAttributionTest.java        creator mod id vs registry namespace
  ModEntryTest.java              search matching and sort orders
```

## Limitations

- **Client only.** Nothing happens on a dedicated server; the mod is not needed there.
- **The unit of filtering is a mod, or a category within a mod.** There is no way to hide one specific
  item; use JEI's own hide-item feature for that.
- The categories are **potions, arrows and enchanted books**. Adding another means editing
  `IngredientCategory` (the enum constant plus its entry in the nested class-predicate map) and adding a
  lang key — the rest of the
  mod picks it up from `SELECTABLE`.
- A mod only appears in the menu once JEI is reporting ingredients for it, which happens when a world
  is loaded. The menu is empty on the title screen.
- Hiding `jei` itself is allowed; it hides JEI's own items. JEI's lookup history is unaffected.
- Changing the selection makes JEI remove or re-add ingredients, so on older JEI a large filter change
  is visible for a moment as the list re-populates.
- The saved config holds one entry per ticked facet, so it grows with the number of mods. That is a few
  hundred entries in a large pack.

## Development notes

Seven defects in this mod were only findable in a large pack, not in the single-mod dev instance. They
are recorded here because each one is a trap worth recognising again:

| Symptom | Cause |
|---|---|
| Modded potions, potion arrows, enchanted books survive "hide minecraft" | Attribution used the registry namespace. A modded potion is `minecraft:potion`; only the creator-mod-id lookup names the mod that added it. |
| Fluids survive "hide everything" | Only `VanillaTypes.ITEM_STACK` was filtered. JEI's fluid ingredients are a separate registered type. |
| `StackOverflowError` on an older JEI | `removeIngredientsAtRuntime`/`addIngredientsAtRuntime` notify ingredient listeners synchronously, and this mod is one. |
| Hidden items reappear seconds later on an older JEI | The catalog was re-read while the filter was applied, so it returned the *filtered* list and the hidden mods looked absent. |
| Items reappear, then disappear again | A menu open rebuilt the catalog in the middle of a hide, so the hidden set was recomputed from a half-applied state. |
| Hiding one category hid the whole mod | The plan worked in whole mods. A category is not expressible as a set of mods, so the unit had to become the ingredient. |
| Items that were not potions or arrows were hidden with the category | Classification matched the registry name as a fallback. A name is a guess: `potion_magazine` is a magazine, `arrow_quiver` is a quiver. Removed; only class inheritance decides now. |

Five lessons that generalise:

- **A hide that "ran" is not a hide that "took".** On the older JEI route the ingredient list is the
  thing being modified, so reading it back after hiding returns the filtered list. The useful
  assertion in a live client is `IIngredientManager#getAllItemStacks().size()` after a hide, not
  whether a hide call was issued.
- **Attribution errors are invisible in small dev instances** and obvious in a large pack, because
  they only show up for ingredients whose registry name disagrees with the mod that added them.
- **A two-level selection needs one rule, not two settings.** "Visible when the mod facet or the
  category facet is ticked" is what makes the category checkbox a master switch over the sub-checkboxes
  and keeps a single source of truth. Any extra flag to remember "the category was turned off" would
  re-introduce the disagreement between what the boxes show and what the filter does.
- **A classification rule that matches too much is worse than one that matches too little.** Hiding a
  category hides everything in it, so one wrongly-classified item costs the player something they never
  chose to hide. That is the whole argument for class-only classification: the class is what the item
  *is*, the name is only what someone called it.
- **Testability constrains design.** The unit-test source set has no Minecraft on its classpath, so
  the decision logic had to be pure to be testable at all. That is why the class predicates live in a
  holder nested class: a lambda written directly in `IngredientCategory` compiles to a method *on that
  class*, so merely loading the enum would then have required Minecraft present — and every test in
  the project would have failed to load.

## License

MIT.
