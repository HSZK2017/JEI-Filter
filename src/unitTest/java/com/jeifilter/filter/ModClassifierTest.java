package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How something is filed into a category.
 *
 * <p>Two rules, in order: the subject's <strong>class</strong>, then its registry name. The class is
 * the real answer — a mod's potion subclasses {@code PotionItem} and its arrow subclasses
 * {@code ArrowItem} — and it covers vanilla too, because every potion in the game is a
 * {@code PotionItem}. Verified with {@code javap} against a 324-mod pack, which is where the shape
 * comes from:
 *
 * <pre>
 *   soulslike-weaponry  CustomPotionItem     extends PotionItem
 *   soulslike-weaponry  CustomSplashPotion   extends CustomPotionItem
 *   iceandfire          ItemDragonArrow      extends ArrowItem
 *   alexsmobs           ItemModArrow         extends ArrowItem
 *   alexscaves          BurrowingArrowItem   extends ArrowItem
 *   goety               BrewArrowItem        extends ArrowItem
 *   goety               UndeathPotionItem    extends Item      &lt;- only the name identifies this one
 * </pre>
 *
 * <p>The name rule is deliberately narrow, and the near-misses are asserted explicitly: hiding a
 * category hides everything in it, so a rule that matches too much costs the player items they never
 * chose to hide.
 *
 * <p>Fake subjects stand in for the item classes, because the unit-test classpath has no Minecraft.
 * What is tested here is the ordering and the name patterns, which is the whole of the decision.
 */
class ModClassifierTest {
	private static final List<IngredientCategory> CATEGORIES = IngredientCategory.SELECTABLE;

	/** A stand-in for an item: its shape is which categories it is an instance of. */
	private record Subject(String name, List<IngredientCategory> isInstanceOf) {
	}

	private static final Subject POTION_CLASS = new Subject("somemod:strange_draught",
		List.of(IngredientCategory.POTION));
	private static final Subject ARROW_CLASS = new Subject("iceandfire:dragon_bone_arrow",
		List.of(IngredientCategory.ARROW));
	private static final Subject BOOK_CLASS = new Subject("minecraft:enchanted_book",
		List.of(IngredientCategory.ENCHANTED_BOOK));
	/** goety's undeath_potion: a plain Item whose registry name is the only clue. */
	private static final Subject PLAIN_ITEM = new Subject("goety:undeath_potion", List.of());

	private static IngredientCategory attribute(Subject subject) {
		return ModClassifier.attribute(subject,
			value -> candidate -> value.isInstanceOf().contains(candidate),
			Subject::name,
			CATEGORIES,
			IngredientCategory.MAIN);
	}

	// ------------------------------------------------------------------
	// the class rule
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a PotionItem subclass is a potion whatever its registry name says")
	void potionByClass() {
		assertEquals(IngredientCategory.POTION, attribute(POTION_CLASS),
			"a potion subclass must be a potion even when its name never mentions potions");
	}

	@Test
	@DisplayName("an ArrowItem subclass is an arrow whatever its registry name says")
	void arrowByClass() {
		assertEquals(IngredientCategory.ARROW, attribute(ARROW_CLASS));
	}

	@Test
	@DisplayName("an EnchantedBookItem subclass is an enchanted book")
	void enchantedBookByClass() {
		assertEquals(IngredientCategory.ENCHANTED_BOOK, attribute(BOOK_CLASS));
	}

	@Test
	@DisplayName("the class rule wins over a misleading registry name")
	void classBeatsName() {
		// A potion subclass whose name mentions no potion at all is still a potion.
		Subject disguised = new Subject("somemod:strange_draught", List.of(IngredientCategory.POTION));
		assertEquals(IngredientCategory.POTION, attribute(disguised));

		// A plain item whose name looks like a potion is not one by class, so the name rule catches it.
		Subject byNameOnly = new Subject("goety:undeath_potion", List.of());
		assertEquals(IngredientCategory.POTION, attribute(byNameOnly));
	}

	// ------------------------------------------------------------------
	// the name fallback
	// ------------------------------------------------------------------

	@Test
	@DisplayName("the name rule catches what the class rule cannot")
	void nameFallbackCatchesNonSubclasses() {
		// goety's undeath_potion item extends plain Item, so only the name identifies it.
		assertEquals(IngredientCategory.POTION, attribute(PLAIN_ITEM));
	}

	@Test
	@DisplayName("vanilla and modded potions match by name")
	void potionsByName() {
		assertEquals(IngredientCategory.POTION, IngredientCategory.classify("potion"));
		assertEquals(IngredientCategory.POTION, IngredientCategory.classify("splash_potion"));
		assertEquals(IngredientCategory.POTION, IngredientCategory.classify("lingering_potion"));
		assertEquals(IngredientCategory.POTION, IngredientCategory.classify("healing_potion"));
	}

	@Test
	@DisplayName("vanilla and modded arrows match by name")
	void arrowsByName() {
		assertEquals(IngredientCategory.ARROW, IngredientCategory.classify("arrow"));
		assertEquals(IngredientCategory.ARROW, IngredientCategory.classify("tipped_arrow"));
		assertEquals(IngredientCategory.ARROW, IngredientCategory.classify("spectral_arrow"));
		assertEquals(IngredientCategory.ARROW, IngredientCategory.classify("explosive_arrow"));
	}

	@Test
	@DisplayName("an item merely starting with 'potion' or 'arrow' is neither")
	void prefixesDoNotMatch() {
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("potion_magazine"),
			"tacz:potion_magazine is a gun magazine, not a potion");
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("potionrack"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("potions_book"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("arrow_quiver"),
			"a quiver holds arrows; it is not one");
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("arrowhead"));
	}

	@Test
	@DisplayName("enchanted books match exactly or as a suffixed variant")
	void enchantedBooks() {
		assertEquals(IngredientCategory.ENCHANTED_BOOK, IngredientCategory.classify("enchanted_book"));
		assertEquals(IngredientCategory.ENCHANTED_BOOK, IngredientCategory.classify("rare_enchanted_book"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("book"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("bookshelf"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("enchanted_book_shelf"),
			"a shelf is not a book, even though its name ends with neither pattern's word boundary");
	}

	// ------------------------------------------------------------------
	// the fallback bucket and the edges
	// ------------------------------------------------------------------

	@Test
	@DisplayName("ordinary subjects are MAIN, and MAIN is not selectable")
	void mainFallback() {
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("stone"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify("cogwheel"));
		assertFalse(IngredientCategory.MAIN.isSelectable(),
			"MAIN is the fallback bucket, not a category the player ticks off");
		assertFalse(IngredientCategory.MAIN.matchesPath("anything"));
	}

	@Test
	@DisplayName("a null or empty path is MAIN rather than an exception")
	void nullOrEmptyPath() {
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify(null));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.classify(""));
	}

	@Test
	@DisplayName("a subject with no class and no name is MAIN")
	void nothingToGoOn() {
		assertEquals(IngredientCategory.MAIN,
			ModClassifier.attribute(null, value -> candidate -> false, value -> null,
				CATEGORIES, IngredientCategory.MAIN));
	}

	@Test
	@DisplayName("only the special categories are selectable")
	void selectableSet() {
		assertEquals(3, IngredientCategory.SELECTABLE.size());
		assertTrue(IngredientCategory.SELECTABLE.contains(IngredientCategory.POTION));
		assertTrue(IngredientCategory.SELECTABLE.contains(IngredientCategory.ARROW));
		assertTrue(IngredientCategory.SELECTABLE.contains(IngredientCategory.ENCHANTED_BOOK));
		assertFalse(IngredientCategory.SELECTABLE.contains(IngredientCategory.MAIN));
	}

	@Test
	@DisplayName("ids round trip, and an unknown id is null rather than a crash")
	void ids() {
		assertEquals(IngredientCategory.POTION, IngredientCategory.byId("potion"));
		assertEquals(IngredientCategory.ENCHANTED_BOOK, IngredientCategory.byId("enchanted_book"));
		assertEquals(IngredientCategory.MAIN, IngredientCategory.byId("main"));
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
	@DisplayName("the first matching category wins, so the order is part of the contract")
	void orderIsDeterministic() {
		// A subject that would satisfy two categories: the earlier one in SELECTABLE takes it.
		Subject both = new Subject("somemod:odd_thing", List.of(IngredientCategory.ARROW, IngredientCategory.POTION));
		assertEquals(IngredientCategory.POTION, attribute(both));

		Predicate<IngredientCategory> none = candidate -> false;
		assertTrue(CATEGORIES.stream().noneMatch(none));
	}
}
