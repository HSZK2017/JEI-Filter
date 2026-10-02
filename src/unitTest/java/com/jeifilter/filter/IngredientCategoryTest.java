package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The category enum's contract.
 *
 * <p>What is <em>not</em> here is the classification itself: it is
 * {@code item instanceof PotionItem} and nothing else, and the unit-test classpath has no Minecraft,
 * so an {@code Item} cannot be constructed to assert on. That the predicates are the real vanilla
 * base types is verified against the built jar instead, with {@code javap} -- and, more importantly,
 * that there is no name matching left to give a false positive.
 *
 * <p>This split is the reason {@code IngredientCategory} keeps its class predicates in a nested class:
 * loading the enum must not require Minecraft, or none of the tests below could run either.
 */
class IngredientCategoryTest {
	@Test
	@DisplayName("only the special categories are selectable, and MAIN is the bucket of everything else")
	void selectableSet() {
		assertEquals(3, IngredientCategory.SELECTABLE.size());
		assertTrue(IngredientCategory.SELECTABLE.contains(IngredientCategory.POTION));
		assertTrue(IngredientCategory.SELECTABLE.contains(IngredientCategory.ARROW));
		assertTrue(IngredientCategory.SELECTABLE.contains(IngredientCategory.ENCHANTED_BOOK));
		assertFalse(IngredientCategory.SELECTABLE.contains(IngredientCategory.MAIN));

		assertFalse(IngredientCategory.MAIN.isSelectable(),
			"MAIN collects everything unclassified; it is not something the player ticks off");
		assertTrue(IngredientCategory.POTION.isSelectable());
		assertTrue(IngredientCategory.ARROW.isSelectable());
		assertTrue(IngredientCategory.ENCHANTED_BOOK.isSelectable());
	}

	@Test
	@DisplayName("the enum loads with no Minecraft on the classpath")
	void enumLoadsWithoutMinecraft() {
		// Not a no-op: this class would fail to load at all if the class predicates were not kept in a
		// nested class, and then every test in this project would fail. The unit-test classpath has no
		// Minecraft, so simply reaching this line is the assertion.
		assertEquals(4, IngredientCategory.values().length);
		assertSame(IngredientCategory.MAIN, IngredientCategory.byId("main"));
	}

	@Test
	@DisplayName("MAIN has no class predicate, so it never claims anything")
	void mainHasNoClassPredicate() {
		assertFalse(IngredientCategory.MAIN.isSelectable(),
			"MAIN is the fallback bucket: it has no class check to match with");
		for (IngredientCategory category : IngredientCategory.SELECTABLE) {
			assertTrue(category.isSelectable(), category + " should be selectable");
		}
	}

	@Test
	@DisplayName("ids round trip, and an unknown id is null rather than a crash")
	void ids() {
		assertEquals(IngredientCategory.POTION, IngredientCategory.byId("potion"));
		assertEquals(IngredientCategory.ARROW, IngredientCategory.byId("arrow"));
		assertEquals(IngredientCategory.ENCHANTED_BOOK, IngredientCategory.byId("enchanted_book"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.byId("main"));
		assertEquals(IngredientCategory.POTION, IngredientCategory.byId("POTION"), "ids parse case-insensitively");
		assertNull(IngredientCategory.byId("no_such_category"));
	}

	@Test
	@DisplayName("every category has a distinct id and translation key")
	void idsAreDistinct() {
		for (IngredientCategory a : IngredientCategory.values()) {
			for (IngredientCategory b : IngredientCategory.values()) {
				if (a != b) {
					assertFalse(a.id().equals(b.id()), a + " and " + b + " share an id");
					assertFalse(a.translationKey().equals(b.translationKey()),
						a + " and " + b + " share a translation key");
				}
			}
		}
	}

	@Test
	@DisplayName("every category id is a valid facet suffix, so it round trips through the config")
	void idsRoundTripThroughFacets() {
		for (IngredientCategory category : IngredientCategory.values()) {
			String facet = FilterOptions.facet("somemod", category);
			assertSame(category, FilterOptions.facetCategory(facet),
				category + " did not survive a facet round trip");
			assertEquals("somemod", FilterOptions.facetModId(facet));
		}
	}
}
