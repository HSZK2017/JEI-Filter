package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FilterOptions#plan}, the decision of which mods JEI is told to hide and which to
 * unhide.
 *
 * <p>These exist because the live mod was once observed doing the exact opposite of its label: the
 * selection was right, {@link FilterOptions#hiddenModIds} was right, and only the hide/unhide sense
 * was crossed. Nothing that inspects the selection alone can catch that, so the transition itself is
 * asserted here.
 */
class FilterPlanTest {
	private static final List<String> LOADED = List.of("minecraft", "create", "mekanism");

	@Test
	@DisplayName("ticking a mod hides it: nothing unhidden, one mod hidden")
	void tickHides() {
		FilterOptions options = FilterOptions.EMPTY.withToggled("create");

		FilterOptions.FilterPlan plan = options.plan(Set.of(), LOADED);

		assertTrue(plan.modsToUnhide().isEmpty(), "nothing was hidden before, so nothing to unhide");
		assertEquals(Set.of("create"), plan.modsToHide(), "the ticked mod must be handed to hideIngredients");
		assertEquals(Set.of("create"), plan.newHiddenSet());
	}

	@Test
	@DisplayName("unticking a mod unhides it: one mod unhidden, nothing hidden")
	void untickUnhides() {
		FilterOptions options = FilterOptions.EMPTY;

		FilterOptions.FilterPlan plan = options.plan(Set.of("create"), LOADED);

		assertEquals(Set.of("create"), plan.modsToUnhide(), "the unticked mod must be handed to unhideIngredients");
		assertTrue(plan.modsToHide().isEmpty(), "hiding nothing is what makes it visible again");
		assertTrue(plan.newHiddenSet().isEmpty());
	}

	@Test
	@DisplayName("a mod stays hidden across an unrelated toggle without being re-sent to JEI")
	void unchangedModIsNotResent() {
		FilterOptions options = FilterOptions.EMPTY.withToggled("create");

		FilterOptions.FilterPlan plan = options.plan(Set.of("create"), LOADED);

		assertTrue(plan.modsToHide().isEmpty());
		assertTrue(plan.modsToUnhide().isEmpty());
		assertEquals(Set.of("create"), plan.newHiddenSet(), "but it stays hidden");
	}

	@Test
	@DisplayName("switching to whitelist unhides a mod that is no longer hidden by the new rule")
	void modeSwitchTransition() {
		// Whichever mod was hidden under the old rule but is not hidden under the new one must be
		// released. Here "mekanism" was hidden by the previous (blacklist) selection and the new
		// whitelist keeps it, while "create" becomes hidden for the first time.
		FilterOptions options = FilterOptions.EMPTY
			.withMode(FilterMode.WHITELIST)
			.withSelection(List.of("minecraft", "mekanism"), true);

		FilterOptions.FilterPlan plan = options.plan(Set.of("mekanism"), LOADED);

		assertEquals(Set.of("mekanism"), plan.modsToUnhide());
		assertEquals(Set.of("create"), plan.modsToHide());
		assertEquals(Set.of("create"), plan.newHiddenSet());
	}

	@Test
	@DisplayName("a mod that stays hidden under the new rule is neither hidden nor unhidden again")
	void modeSwitchKeepsStillHiddenMod() {
		FilterOptions options = FilterOptions.EMPTY
			.withMode(FilterMode.WHITELIST)
			.withSelection(List.of("minecraft"), true);

		FilterOptions.FilterPlan plan = options.plan(Set.of("create"), LOADED);

		assertTrue(plan.modsToUnhide().isEmpty(), "create stays hidden under the whitelist, so nothing to unhide");
		assertEquals(Set.of("mekanism"), plan.modsToHide());
		assertEquals(Set.of("create", "mekanism"), plan.newHiddenSet());
	}

	@Test
	@DisplayName("a dropped mod is unhidden but stays ticked; a mod still in JEI is hidden")
	void droppedModIsUnhiddenRenamed() {
		// "create" is ticked and was hidden, but JEI no longer lists it.
		FilterOptions options = FilterOptions.EMPTY.withToggled("create");

		FilterOptions.FilterPlan plan = options.plan(Set.of("create"), List.of("minecraft", "mekanism"));

		assertEquals(Set.of("create"), plan.modsToUnhide(), "a mod that left JEI must not stay hidden");
		assertTrue(plan.modsToHide().isEmpty());
		assertTrue(plan.newHiddenSet().isEmpty(), "the set is over the loaded mods, so it is empty now");
	}

	@Test
	@DisplayName("a mod that appears in JEI while ticked is hidden without touching the other mods")
	void newModIsHidden() {
		// "create" was ticked before JEI reported it; now JEI reports it for the first time.
		FilterOptions options = FilterOptions.EMPTY.withToggled("create");

		FilterOptions.FilterPlan plan = options.plan(Set.of(), LOADED);

		assertTrue(plan.modsToUnhide().isEmpty());
		assertEquals(Set.of("create"), plan.modsToHide());
		assertEquals(Set.of("create"), plan.newHiddenSet());
	}

	@Test
	@DisplayName("clearing a blacklist unhides everything that was hidden")
	void clearingUnhidesEverything() {
		FilterOptions options = FilterOptions.EMPTY;

		FilterOptions.FilterPlan plan = options.plan(Set.of("create", "mekanism"), LOADED);

		assertEquals(Set.of("create", "mekanism"), plan.modsToUnhide());
		assertTrue(plan.modsToHide().isEmpty());
	}

	@Test
	@DisplayName("an empty whitelist hides every loaded mod and unhides nothing")
	void emptyWhitelistHidesAll() {
		FilterOptions options = FilterOptions.EMPTY.withMode(FilterMode.WHITELIST);

		FilterOptions.FilterPlan plan = options.plan(Set.of(), LOADED);

		assertEquals(Set.of("minecraft", "create", "mekanism"), plan.modsToHide());
		assertTrue(plan.modsToUnhide().isEmpty());
	}

	@Test
	@DisplayName("the new hidden set always equals hiddenModIds of the loaded list")
	void planIsConsistentWithHiddenModIds() {
		FilterOptions options = FilterOptions.EMPTY.withToggled("mekanism");

		FilterOptions.FilterPlan plan = options.plan(Set.of("create"), LOADED);

		assertEquals(options.hiddenModIds(LOADED), plan.newHiddenSet());
	}

	@Test
	@DisplayName("a mod is never both hidden and unhidden in the same transition")
	void neverBoth() {
		FilterOptions options = FilterOptions.EMPTY
			.withMode(FilterMode.WHITELIST)
			.withSelection(List.of("create"), true);

		FilterOptions.FilterPlan plan = options.plan(Set.of("minecraft", "mekanism"), LOADED);

		for (String modId : plan.modsToHide()) {
			assertTrue(!plan.modsToUnhide().contains(modId), modId + " must not be hidden and unhidden at once");
		}
	}
}
