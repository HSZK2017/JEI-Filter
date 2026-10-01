package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModEntryTest {
	@Test
	@DisplayName("an empty query matches everything")
	void emptyQueryMatches() {
		assertTrue(new ModEntry("create", "Create", 10, ModEntry.Kind.MOD).matches(""));
	}

	@Test
	@DisplayName("matching is case insensitive on both name and id")
	void caseInsensitive() {
		ModEntry entry = new ModEntry("mekanism", "Mekanism", 10, ModEntry.Kind.MOD);
		assertTrue(entry.matches("mek"));
		assertTrue(entry.matches("MEK"));
		assertTrue(entry.matches("Mekanism".toLowerCase(Locale.ROOT)));
		assertFalse(entry.matches("thermal"));
	}

	@Test
	@DisplayName("matching also finds substrings in the middle")
	void substring() {
		ModEntry entry = new ModEntry("appliedenergistics2", "Applied Energistics 2", 10, ModEntry.Kind.MOD);
		assertTrue(entry.matches("energ"));
		assertTrue(entry.matches("istics"));
	}

	@Test
	@DisplayName("the default order groups Minecraft, then JEI, then other mods by display name")
	void displayOrder() {
		ModEntry jei = new ModEntry("jei", "Just Enough Items", 5, ModEntry.Kind.JEI);
		ModEntry minecraft = new ModEntry("minecraft", "Minecraft", 900, ModEntry.Kind.MINECRAFT);
		ModEntry zebra = new ModEntry("zebra", "Zebra Mod", 1, ModEntry.Kind.MOD);
		ModEntry apple = new ModEntry("apple", "Apple Mod", 1, ModEntry.Kind.MOD);

		List<ModEntry> sorted = new java.util.ArrayList<>(List.of(jei, zebra, minecraft, apple));
		sorted.sort(ModEntry.BY_NAME);

		assertEquals(List.of(minecraft, jei, apple, zebra), sorted);
	}

	@Test
	@DisplayName("mod ids are the tie breaker when two mods share a display name")
	void displayOrderTieBreaksOnModId() {
		ModEntry beta = new ModEntry("beta", "Same Name", 1, ModEntry.Kind.MOD);
		ModEntry alpha = new ModEntry("alpha", "Same Name", 1, ModEntry.Kind.MOD);

		List<ModEntry> sorted = new java.util.ArrayList<>(List.of(beta, alpha));
		sorted.sort(ModEntry.BY_NAME);

		assertEquals(List.of(alpha, beta), sorted);
	}

	@Test
	@DisplayName("sorting by item count puts the largest mods first")
	void itemCountOrder() {
		ModEntry small = new ModEntry("a", "A", 1, ModEntry.Kind.MOD);
		ModEntry big = new ModEntry("b", "B", 900, ModEntry.Kind.MOD);
		ModEntry medium = new ModEntry("c", "C", 50, ModEntry.Kind.MOD);

		List<ModEntry> sorted = new java.util.ArrayList<>(List.of(small, big, medium));
		sorted.sort(ModEntry.BY_ITEM_COUNT);

		assertEquals(List.of(big, medium, small), sorted);
	}

	@Test
	@DisplayName("sort modes cycle through all three orders")
	void sortCycles() {
		assertEquals(ModEntry.Sort.COUNT, ModEntry.Sort.NAME.next());
		assertEquals(ModEntry.Sort.MOD_ID, ModEntry.Sort.COUNT.next());
		assertEquals(ModEntry.Sort.NAME, ModEntry.Sort.MOD_ID.next());
	}
}
