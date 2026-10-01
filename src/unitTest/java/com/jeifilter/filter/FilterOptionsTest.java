package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/**
 * The selection model: two axes (mod, and category-within-mod), their three-state boxes, and the
 * syncing between a category's master box and the per-mod sub-boxes.
 *
 * <p>One rule throughout: <strong>a ticked box means "show it"</strong>. Nothing is hidden unless it
 * is unticked, and a box never means the opposite of what it looks like.
 */
class FilterOptionsTest {
	private static final List<String> MODS = List.of("minecraft", "create", "mekanism");
	private static final List<IngredientCategory> CATEGORIES =
		List.of(IngredientCategory.POTION, IngredientCategory.ARROW);

	// ------------------------------------------------------------------
	// the rule
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a fresh selection hides everything, and the service ticks the loaded mods to fix that")
	void emptyHidesEverything() {
		assertTrue(FilterOptions.EMPTY.ticked().isEmpty());
		assertEquals(ingredients().keySet(), FilterOptions.EMPTY.hiddenIngredients(ingredients()));
	}

	@Test
	@DisplayName("a ticked mod shows everything it has")
	void tickedModShowsAll() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", true, CATEGORIES);

		assertTrue(options.isModTicked("create"));
		assertTrue(options.isCategoryVisible("create", IngredientCategory.POTION));
		assertTrue(options.isCategoryVisible("create", IngredientCategory.ARROW));
		assertTrue(options.hiddenIngredients(ingredients()).isEmpty(),
			"every ingredient here belongs to create");
	}

	@Test
	@DisplayName("an unticked mod hides everything it has")
	void untickedModHidesAll() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", true, CATEGORIES)
			.withModTicked("create", false, CATEGORIES);

		assertFalse(options.isModTicked("create"));
		assertEquals(ingredients().keySet(), options.hiddenIngredients(ingredients()));
	}

	@Test
	@DisplayName("unticking one category hides only that category of that mod")
	void untickedCategoryHidesOnlyThat() {
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("create", IngredientCategory.POTION, false);

		assertFalse(options.isCategoryVisible("create", IngredientCategory.POTION));
		assertTrue(options.isCategoryVisible("create", IngredientCategory.ARROW),
			"unticking potions must leave arrows alone");
		assertTrue(options.isCategoryVisible("create", IngredientCategory.MAIN),
			"unticking potions must leave the mod's ordinary items alone");
		assertEquals(Set.of(key("potion")), options.hiddenIngredients(ingredients()));
	}

	@Test
	@DisplayName("unticking a category works even when the whole mod was ticked first")
	void untickingCategoryBeatsStaleModTick() {
		// The bug this guards: the mod facet outranks a category facet, so unticking a sub-box used to
		// do nothing whenever the mod was ticked as a whole. The exception set is what makes the
		// sub-box effective without also hiding the mod's other categories.
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("create", IngredientCategory.POTION, false);

		assertTrue(options.isModTicked("create"), "the mod stays ticked, so its other items stay shown");
		assertTrue(options.isCategoryUnticked("create", IngredientCategory.POTION));
		assertFalse(options.isCategoryVisible("create", IngredientCategory.POTION),
			"the sub-box must actually take effect");
		assertTrue(options.isCategoryVisible("create", IngredientCategory.ARROW),
			"and must not take the mod's other categories with it");
	}

	@Test
	@DisplayName("ticking a mod clears its per-category boxes, so no stale tick outranks it")
	void tickingModClearsCategories() {
		FilterOptions options = FilterOptions.EMPTY
			.withCategoryTicked("create", IngredientCategory.POTION, true)
			.withModTicked("create", true, CATEGORIES);

		assertTrue(options.isModTicked("create"));
		assertFalse(options.isCategoryTicked("create", IngredientCategory.POTION),
			"the mod facet already covers it; leaving it set is what made sub-boxes unclickable");
	}

	// ------------------------------------------------------------------
	// three-state boxes
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a category reads ALL only when every mod is ticked for it")
	void categoryStateAll() {
		FilterOptions options = FilterOptions.EMPTY
			.withCategoryTicked("minecraft", IngredientCategory.POTION, true)
			.withCategoryTicked("create", IngredientCategory.POTION, true);

		assertEquals(FilterOptions.TickState.ALL,
			options.categoryState(IngredientCategory.POTION, List.of("minecraft", "create")));
	}

	@Test
	@DisplayName("a category reads SOME when only some mods are ticked for it")
	void categoryStateSome() {
		FilterOptions options = FilterOptions.EMPTY
			.withCategoryTicked("minecraft", IngredientCategory.POTION, true);

		assertEquals(FilterOptions.TickState.SOME,
			options.categoryState(IngredientCategory.POTION, List.of("minecraft", "create")));
	}

	@Test
	@DisplayName("a category reads NONE when no mod is ticked for it")
	void categoryStateNone() {
		assertEquals(FilterOptions.TickState.NONE,
			FilterOptions.EMPTY.categoryState(IngredientCategory.POTION, List.of("minecraft", "create")));
	}

	@Test
	@DisplayName("a category with no mods reads NONE rather than ALL")
	void emptyCategoryIsNone() {
		assertEquals(FilterOptions.TickState.NONE,
			FilterOptions.EMPTY.categoryState(IngredientCategory.POTION, List.of()));
	}

	@Test
	@DisplayName("a ticked whole mod makes every one of its categories read ALL")
	void modTickCoversCategories() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", true, CATEGORIES);

		assertEquals(FilterOptions.TickState.ALL, options.modState("create", CATEGORIES));
		assertEquals(FilterOptions.TickState.ALL,
			options.categoryState(IngredientCategory.POTION, List.of("create")));
	}

	@Test
	@DisplayName("a mod with only some categories ticked reads SOME")
	void modStateSome() {
		FilterOptions options = FilterOptions.EMPTY
			.withCategoryTicked("create", IngredientCategory.POTION, true);

		assertEquals(FilterOptions.TickState.SOME, options.modState("create", CATEGORIES));
	}

	@Test
	@DisplayName("a mod with all its categories ticked reads ALL")
	void modStateAllFromCategories() {
		FilterOptions options = FilterOptions.EMPTY
			.withCategoryTicked("create", IngredientCategory.POTION, true)
			.withCategoryTicked("create", IngredientCategory.ARROW, true);

		assertEquals(FilterOptions.TickState.ALL, options.modState("create", CATEGORIES));
	}

	// ------------------------------------------------------------------
	// the master box driving the sub-boxes
	// ------------------------------------------------------------------

	@Test
	@DisplayName("ticking a category ticks it for every mod that has it")
	void categoryMasterTicksAll() {
		FilterOptions after = FilterOptions.EMPTY.withCategoryTickedEverywhere(
			IngredientCategory.POTION, List.of("minecraft", "create"), true);

		assertTrue(after.isCategoryTicked("minecraft", IngredientCategory.POTION));
		assertTrue(after.isCategoryTicked("create", IngredientCategory.POTION));
		assertEquals(FilterOptions.TickState.ALL,
			after.categoryState(IngredientCategory.POTION, List.of("minecraft", "create")));
	}

	@Test
	@DisplayName("unticking a category unticks it for every mod that has it")
	void categoryMasterUnticksAll() {
		FilterOptions options = FilterOptions.EMPTY
			.withCategoryTicked("minecraft", IngredientCategory.POTION, true)
			.withCategoryTicked("create", IngredientCategory.POTION, true)
			.withCategoryTicked("create", IngredientCategory.ARROW, true);

		FilterOptions after = options.withCategoryTickedEverywhere(
			IngredientCategory.POTION, List.of("minecraft", "create"), false);

		assertFalse(after.isCategoryTicked("minecraft", IngredientCategory.POTION));
		assertFalse(after.isCategoryTicked("create", IngredientCategory.POTION));
		assertTrue(after.isCategoryTicked("create", IngredientCategory.ARROW),
			"unticking one category must leave the other categories alone");
		assertEquals(FilterOptions.TickState.NONE,
			after.categoryState(IngredientCategory.POTION, List.of("minecraft", "create")));
	}

	@Test
	@DisplayName("unticking a category turns it off for every mod and leaves the mods themselves ticked")
	void categoryMasterOverridesModTick() {
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("minecraft", true, CATEGORIES)
			.withModTicked("create", true, CATEGORIES);

		FilterOptions after = options.withCategoryTickedEverywhere(
			IngredientCategory.POTION, List.of("minecraft", "create"), false);

		assertTrue(after.isModTicked("minecraft"), "the mods stay ticked, so their other items stay");
		assertTrue(after.isModTicked("create"), "the mods stay ticked, so their other items stay");
		assertFalse(after.isCategoryVisible("minecraft", IngredientCategory.POTION));
		assertFalse(after.isCategoryVisible("create", IngredientCategory.POTION));
		assertTrue(after.isCategoryVisible("create", IngredientCategory.ARROW),
			"unticking the potion category must not hide arrows");
	}

	@Test
	@DisplayName("one mod's category can be overridden after a master tick")
	void subBoxOverridesMaster() {
		FilterOptions all = FilterOptions.EMPTY.withCategoryTickedEverywhere(
			IngredientCategory.POTION, MODS, true);

		FilterOptions after = all.withCategoryTicked("create", IngredientCategory.POTION, false);

		assertTrue(after.isCategoryTicked("minecraft", IngredientCategory.POTION));
		assertFalse(after.isCategoryTicked("create", IngredientCategory.POTION));
		assertTrue(after.isCategoryTicked("mekanism", IngredientCategory.POTION));
		assertEquals(FilterOptions.TickState.SOME,
			after.categoryState(IngredientCategory.POTION, MODS));
	}

	// ------------------------------------------------------------------
	// the hide plan
	// ------------------------------------------------------------------

	@Test
	@DisplayName("unticking a category hides exactly its ingredients, and nothing else")
	void planHidesOneCategory() {
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("create", IngredientCategory.POTION, false);

		FilterOptions.IngredientPlan plan = options.plan(Set.of(), ingredients());

		assertEquals(Set.of(key("potion")), plan.toHide());
		assertTrue(plan.toUnhide().isEmpty());
	}

	@Test
	@DisplayName("unticking a mod hides everything it has")
	void planHidesWholeMod() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", false, CATEGORIES);

		FilterOptions.IngredientPlan plan = options.plan(Set.of(), ingredients());

		assertEquals(Set.of(key("potion"), key("arrow"), key("block")), plan.toHide());
	}

	@Test
	@DisplayName("re-ticking a mod unhides everything it has again")
	void planUnhidesWholeMod() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", true, CATEGORIES);

		FilterOptions.IngredientPlan plan = options.plan(
			Set.of(key("potion"), key("arrow"), key("block")), ingredients());

		assertEquals(Set.of(key("potion"), key("arrow"), key("block")), plan.toUnhide());
		assertTrue(plan.toHide().isEmpty());
	}

	@Test
	@DisplayName("re-ticking a category unhides exactly its ingredients")
	void planUnhidesOneCategory() {
		// Turn it off, then on again: the exception must be cleared, not merely shadowed.
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("create", IngredientCategory.POTION, false)
			.withCategoryTicked("create", IngredientCategory.POTION, true);

		assertTrue(options.isCategoryVisible("create", IngredientCategory.POTION));
		assertFalse(options.isCategoryUnticked("create", IngredientCategory.POTION));

		FilterOptions.IngredientPlan plan = options.plan(Set.of(key("potion")), ingredients());

		assertEquals(Set.of(key("potion")), plan.toUnhide());
		assertTrue(plan.toHide().isEmpty());
	}

	@Test
	@DisplayName("hiding one category leaves the rest of that mod visible")
	void planKeepsRestOfModVisible() {
		// The regression this guards: hiding "every potion" must not take the mod's other items too.
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("create", IngredientCategory.POTION, false);

		FilterOptions.IngredientPlan plan = options.plan(Set.of(), ingredients());

		assertEquals(Set.of(key("potion")), plan.toHide());
		assertFalse(plan.toHide().contains(key("block")), "an ordinary item must stay visible");
		assertFalse(plan.toHide().contains(key("arrow")), "a different category must stay visible");
	}

	@Test
	@DisplayName("the plan's new hidden set is exactly what the selection says is hidden")
	void planIsConsistent() {
		FilterOptions options = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("create", IngredientCategory.ARROW, false);

		FilterOptions.IngredientPlan plan = options.plan(Set.of(), ingredients());

		assertEquals(options.hiddenIngredients(ingredients()), plan.newHiddenSet());
		assertEquals(Set.of(key("arrow")), plan.newHiddenSet());
		assertEquals(Set.of(key("arrow")), plan.toHide());
		assertTrue(plan.toUnhide().isEmpty());
	}

	// ------------------------------------------------------------------
	// newly loaded mods
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a mod first seen later is ticked, so it is not silently missing")
	void newModsAreTicked() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", true, CATEGORIES);

		FilterOptions after = options.withNewModsTicked(Set.of("create"), List.of("create", "newmod"));

		assertTrue(after.isModTicked("newmod"),
			"without this, a mod that loads later would be hidden the moment it appears");
		assertTrue(after.isCategoryVisible("newmod", IngredientCategory.POTION),
			"and ticking the mod is enough to show everything it has");
	}

	@Test
	@DisplayName("known mods are left alone when bringing in new ones")
	void newModsLeavesKnownModsAlone() {
		FilterOptions options = FilterOptions.EMPTY.withModTicked("create", true, CATEGORIES);

		FilterOptions after = options.withNewModsTicked(Set.of("create"), List.of("create"));

		assertTrue(after.isModTicked("create"), "a known mod's tick state must survive");
		assertEquals(options, after, "nothing new means nothing changed");
	}

	// ------------------------------------------------------------------
	// persistence
	// ------------------------------------------------------------------

	@Test
	@DisplayName("json round trip preserves every facet")
	void jsonRoundTrip() {
		FilterOptions original = FilterOptions.EMPTY
			.withModTicked("create", true, CATEGORIES)
			.withCategoryTicked("mekanism", IngredientCategory.ARROW, true);

		FilterOptions parsed = FilterOptions.fromJson(original.toJson());

		assertEquals(original, parsed);
		assertEquals(original.hashCode(), parsed.hashCode());
		assertTrue(parsed.isModTicked("create"));
		assertTrue(parsed.isCategoryTicked("mekanism", IngredientCategory.ARROW));
	}

	@Test
	@DisplayName("malformed config json falls back to the default instead of throwing")
	void malformedJsonFallsBack() {
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(null));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("null")));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("42")));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("{}")));
		assertEquals(FilterOptions.EMPTY,
			FilterOptions.fromJson(JsonParser.parseString("{\"ticked\":\"nope\"}")));
	}

	@Test
	@DisplayName("blank and non-string entries are dropped when parsing")
	void parsingDropsJunk() {
		FilterOptions parsed = FilterOptions.fromJson(JsonParser.parseString(
			"{\"ticked\":[\"create\",\"\",\"  \",7,null,\"jei|potion\"]}"));

		assertEquals(Set.of("create", "jei|potion"), parsed.ticked());
	}

	@Test
	@DisplayName("configs written by the older two-mode versions still load their faceted tick list")
	void legacyConfigsLoad() {
		// "tickedMods" and "selected" both held whole-mod keys, which are still whole-mod facets.
		FilterOptions fromTickedMods = FilterOptions.fromJson(JsonParser.parseString(
			"{\"mode\":\"blacklist\",\"tickedMods\":[\"create\",\"jei|potion\"]}"));
		assertEquals(Set.of("create", "jei|potion"), fromTickedMods.ticked());

		FilterOptions fromSelected = FilterOptions.fromJson(JsonParser.parseString(
			"{\"mode\":\"whitelist\",\"selected\":[\"create\",\"mekanism\"]}"));
		assertTrue(fromSelected.isModTicked("create"));
		assertTrue(fromSelected.isModTicked("mekanism"));
		assertFalse(fromSelected.isModTicked("minecraft"));
	}

	@Test
	@DisplayName("facet keys round trip through parsing")
	void facetKeys() {
		String facet = FilterOptions.facet("create", IngredientCategory.POTION);
		assertEquals("create|potion", facet);
		assertEquals("create", FilterOptions.facetModId(facet));
		assertEquals(IngredientCategory.POTION, FilterOptions.facetCategory(facet));
		assertNull(FilterOptions.facetModId("nodivider"));
		assertNull(FilterOptions.facetCategory("create|not_a_category"));
	}

	@Test
	@DisplayName("with* is immutable")
	void immutable() {
		FilterOptions options = FilterOptions.EMPTY;
		options.withModTicked("create", true, CATEGORIES);

		assertTrue(options.ticked().isEmpty());
		assertNotEquals(options, options.withModTicked("create", true, CATEGORIES));
	}

	/** create has a potion, an arrow and an ordinary block; nothing else is loaded. */
	private static Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> ingredients() {
		Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> map = new LinkedHashMap<>();
		map.put(key("potion"), new FilterOptions.IngredientInfo("create", IngredientCategory.POTION));
		map.put(key("arrow"), new FilterOptions.IngredientInfo("create", IngredientCategory.ARROW));
		map.put(key("block"), new FilterOptions.IngredientInfo("create", IngredientCategory.MAIN));
		return map;
	}

	private static FilterOptions.IngredientKey key(String name) {
		return new FilterOptions.IngredientKey("create", name);
	}
}
