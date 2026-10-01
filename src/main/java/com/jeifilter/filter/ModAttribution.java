package com.jeifilter.filter;

import java.util.function.Function;

/**
 * Decides which mod an ingredient belongs to.
 *
 * <p>Kept free of JEI and Minecraft types so the rule itself is unit testable: getting it wrong is
 * what made the mod look broken in a large pack.
 *
 * <p>The rule mirrors JEI's own
 * {@code mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper#getDisplayModId}, which asks
 * the platform for the ingredient's <em>creator</em> mod id (Forge: {@code Item#getCreatorModId})
 * and only falls back to the registry namespace when that is empty.
 *
 * <p>Using the registry namespace alone is wrong for exactly the cases that matter: a modded potion
 * is still registered as {@code minecraft:potion} and a modded enchanted book as
 * {@code minecraft:enchanted_book}; the creating mod is only visible through the creator lookup. A
 * namespace-only rule files both under {@code minecraft}, so "hide minecraft" leaves them on screen.
 */
public final class ModAttribution {
	private ModAttribution() {
	}

	/**
	 * @param ingredient    the ingredient being attributed
	 * @param creatorModId  asks the platform for the creating mod, may return null or throw
	 * @param namespace     the namespace of the ingredient's registry name, the fallback
	 * @return the mod id to file the ingredient under, or null when neither source yields anything
	 */
	public static <T> String attribute(T ingredient,
									   Function<T, String> creatorModId,
									   Function<T, String> namespace) {
		String creator = null;
		try {
			creator = creatorModId.apply(ingredient);
		} catch (RuntimeException e) {
			// Fall through to the namespace; one bad helper must not cost us the ingredient.
		}
		if (creator != null && !creator.isBlank()) {
			return creator;
		}
		String fallback;
		try {
			fallback = namespace.apply(ingredient);
		} catch (RuntimeException e) {
			return null;
		}
		if (fallback == null || fallback.isEmpty()) {
			return null;
		}
		return fallback;
	}
}
