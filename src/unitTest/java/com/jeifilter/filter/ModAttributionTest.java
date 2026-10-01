package com.jeifilter.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the rule that decides which mod an ingredient belongs to.
 *
 * <p>This is the bug that made the mod look broken in a large pack: a modded potion's registry name
 * is still {@code minecraft:potion}, and a modded enchanted book's is still
 * {@code minecraft:enchanted_book}, so attributing by registry namespace alone filed them under
 * {@code minecraft}. Whitelisting "only minecraft" then left exactly those items on screen. The
 * creator-mod-id lookup is what identifies the real mod, and it has to win.
 *
 * <p>Only the precedence is covered here — the real creator-mod-id lookup for an {@code ItemStack}
 * is Forge's {@code Item#getCreatorModId} and needs a running game. What is asserted is that the
 * answer is preferred over the namespace, and that the namespace is still used when it is absent.
 */
class ModAttributionTest {
	private static final Object INGREDIENT = new Object();

	private static Function<Object, String> constant(String value) {
		return ignored -> value;
	}

	@Test
	@DisplayName("the creator mod id wins over the registry namespace")
	void creatorModIdWins() {
		// A modded potion: registry namespace is "minecraft", the creator is not.
		String modId = ModAttribution.attribute(INGREDIENT, constant("some_mod"), constant("minecraft"));

		assertEquals("some_mod", modId,
			"a modded potion must not be filed under minecraft just because its registry name is");
	}

	@Test
	@DisplayName("the namespace is used when there is no creator mod id")
	void namespaceIsTheFallback() {
		assertEquals("minecraft", ModAttribution.attribute(INGREDIENT, constant(null), constant("minecraft")));
	}

	@Test
	@DisplayName("a blank or empty creator mod id falls back to the namespace")
	void blankCreatorFallsBack() {
		assertEquals("create", ModAttribution.attribute(INGREDIENT, constant(""), constant("create")));
		assertEquals("create", ModAttribution.attribute(INGREDIENT, constant("   "), constant("create")));
	}

	@Test
	@DisplayName("a creator mod id lookup that throws falls back to the namespace")
	void throwingCreatorFallsBack() {
		Function<Object, String> throwsUp = ignored -> {
			throw new IllegalStateException("helper blew up");
		};

		assertEquals("create", ModAttribution.attribute(INGREDIENT, throwsUp, constant("create")));
	}

	@Test
	@DisplayName("nothing usable yields no attribution, so the ingredient is skipped")
	void nothingUsableYieldsNull() {
		assertNull(ModAttribution.attribute(INGREDIENT, constant(null), constant(null)));
		assertNull(ModAttribution.attribute(INGREDIENT, constant(null), constant("")));
	}

	@Test
	@DisplayName("a real minecraft item is still attributed to minecraft")
	void realMinecraftItemIsMinecraft() {
		// Vanilla items report no creator mod id, and their namespace really is minecraft.
		assertEquals("minecraft", ModAttribution.attribute(INGREDIENT, constant(null), constant("minecraft")));
	}
}
