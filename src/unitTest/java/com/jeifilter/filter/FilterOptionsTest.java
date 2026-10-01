package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class FilterOptionsTest {
	private static final List<String> LOADED = List.of("minecraft", "jei", "create", "mekanism");

	@Test
	@DisplayName("default state hides nothing")
	void defaultHidesNothing() {
		FilterOptions options = FilterOptions.EMPTY;
		assertEquals(FilterMode.BLACKLIST, options.mode());
		assertTrue(options.hiddenModIds(LOADED).isEmpty());
		assertTrue(options.isEmpty());
	}

	@Test
	@DisplayName("blacklist hides exactly the checked mods")
	void blacklistHidesCheckedMods() {
		FilterOptions options = FilterOptions.EMPTY.withToggled("create");
		assertEquals(Set.of("create"), options.hiddenModIds(LOADED));
		assertFalse(options.shouldHide("mekanism"));
	}

	@Test
	@DisplayName("whitelist shows only the checked mods and hides everything else")
	void whitelistShowsOnlyCheckedMods() {
		FilterOptions options = FilterOptions.EMPTY
			.withMode(FilterMode.WHITELIST)
			.withSelection(List.of("minecraft", "jei"), true);

		assertEquals(Set.of("create", "mekanism"), options.hiddenModIds(LOADED));
		assertFalse(options.shouldHide("minecraft"));
		assertTrue(options.shouldHide("mekanism"));
	}

	@Test
	@DisplayName("an empty whitelist hides every loaded mod")
	void emptyWhitelistHidesEverything() {
		FilterOptions options = FilterOptions.EMPTY.withMode(FilterMode.WHITELIST);
		assertEquals(Set.copyOf(LOADED), options.hiddenModIds(LOADED));
	}

	@Test
	@DisplayName("an empty blacklist hides nothing even after clearing a selection")
	void clearedBlacklistHidesNothing() {
		FilterOptions options = FilterOptions.EMPTY
			.withSelection(List.of("create", "mekanism"), true)
			.withSelection(List.of("create", "mekanism"), false);
		assertTrue(options.hiddenModIds(LOADED).isEmpty());
	}

	@Test
	@DisplayName("mods that are not loaded never appear in the hidden set")
	void selectionMayNameUnloadedMods() {
		FilterOptions options = FilterOptions.EMPTY.withSelection(List.of("not_installed"), true);
		assertTrue(options.hiddenModIds(LOADED).isEmpty());
		assertTrue(options.shouldHide("not_installed"), "the stale selection is remembered for later");
	}

	@Test
	@DisplayName("toggle flips a mod on and off")
	void toggleFlips() {
		FilterOptions options = FilterOptions.EMPTY;
		options = options.withToggled("create");
		assertTrue(options.isSelected("create"));
		options = options.withToggled("create");
		assertFalse(options.isSelected("create"));
	}

	@Test
	@DisplayName("invert selection flips every named mod and leaves the rest alone")
	void invertSelection() {
		FilterOptions options = FilterOptions.EMPTY
			.withSelection(List.of("create"), true)
			.withInvertedSelection(List.of("create", "mekanism"));

		assertFalse(options.isSelected("create"));
		assertTrue(options.isSelected("mekanism"));
		assertFalse(options.isSelected("minecraft"));
	}

	@Test
	@DisplayName("changing the mode keeps the selection")
	void modeChangeKeepsSelection() {
		FilterOptions options = FilterOptions.EMPTY.withToggled("create").withMode(FilterMode.WHITELIST);
		assertEquals(Set.of("create"), options.selectedModIds());
		assertEquals(FilterMode.WHITELIST, options.mode());
	}

	@Test
	@DisplayName("json round trip preserves mode and selection")
	void jsonRoundTrip() {
		FilterOptions original = FilterOptions.EMPTY
			.withToggled("create")
			.withToggled("mekanism")
			.withMode(FilterMode.WHITELIST);

		FilterOptions parsed = FilterOptions.fromJson(original.toJson());
		assertEquals(original, parsed);
		assertEquals(original.hashCode(), parsed.hashCode());
	}

	@Test
	@DisplayName("malformed config json falls back to the default instead of throwing")
	void malformedJsonFallsBack() {
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(null));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("null")));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("42")));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("{}")));
		assertEquals(FilterOptions.EMPTY, FilterOptions.fromJson(JsonParser.parseString("{\"selected\":\"nope\"}")));
		assertEquals(FilterMode.BLACKLIST,
			FilterOptions.fromJson(JsonParser.parseString("{\"mode\":\"banana\"}")).mode());
	}

	@Test
	@DisplayName("blank and non-string selections are dropped when parsing")
	void parsingDropsJunk() {
		FilterOptions parsed = FilterOptions.fromJson(JsonParser.parseString(
			"{\"mode\":\"whitelist\",\"selected\":[\"create\",\"\",\"  \",7,null,\"jei\"]}"));
		assertEquals(FilterMode.WHITELIST, parsed.mode());
		assertEquals(Set.of("create", "jei"), parsed.selectedModIds());
	}

	@Test
	@DisplayName("with* is immutable")
	void immutable() {
		FilterOptions options = FilterOptions.EMPTY;
		options.withToggled("create");
		assertTrue(options.selectedModIds().isEmpty());
		assertNotEquals(options, options.withToggled("create"));
	}
}
