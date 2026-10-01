package com.jeifilter.filter;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/**
 * The decision of which {@link IngredientCategory} something belongs to, as a pure function.
 *
 * <p>Deliberately free of Minecraft and JEI types: the unit-test source set has no Minecraft on its
 * classpath, so anything touching an {@code Item} or an {@code ItemStack} there would be untestable.
 * {@link IngredientCategory} supplies the real predicates; this class owns the ordering, which is the
 * part worth testing.
 *
 * <h2>The ordering</h2>
 *
 * <p><strong>Class before name.</strong> A mod's potion subclasses {@code PotionItem} and its arrow
 * subclasses {@code ArrowItem}; that is the authoritative answer, and it covers vanilla too, because
 * every potion in the game <em>is</em> a {@code PotionItem}. The name is only the fallback, for
 * something that is clearly one of these things without subclassing the base — goety's
 * {@code undeath_potion} item extends plain {@code Item}.
 *
 * <p>The name rule is deliberately narrow, because hiding a category hides everything in it: a rule
 * that matches too much costs the player items they never chose to hide. {@code splash_potion} is a
 * potion; {@code potion_magazine} is a gun magazine.
 */
public final class ModClassifier {
	private ModClassifier() {
	}

	/**
	 * @param subject  the thing being classified
	 * @param byClass  whether the subject is an instance of a category's base type
	 * @param byName   the subject's registry path, or null when it has none
	 * @param category the ordered categories to try
	 * @param fallback returned when nothing claims the subject
	 */
	public static <T> IngredientCategory attribute(@Nullable T subject,
												   Function<T, Predicate<IngredientCategory>> byClass,
												   Function<T, String> byName,
												   List<IngredientCategory> category,
												   IngredientCategory fallback) {
		if (subject != null) {
			Predicate<IngredientCategory> classCheck = byClass.apply(subject);
			for (IngredientCategory candidate : category) {
				if (classCheck.test(candidate)) {
					return candidate;
				}
			}
		}
		String path = subject == null ? null : byName.apply(subject);
		if (path == null || path.isEmpty()) {
			return fallback;
		}
		for (IngredientCategory candidate : category) {
			if (candidate.matchesPath(path)) {
				return candidate;
			}
		}
		return fallback;
	}

	/**
	 * The name rule on its own, for subjects that have no class to inspect — a fluid, or another
	 * mod's custom ingredient type.
	 */
	public static IngredientCategory classify(@Nullable String path, List<IngredientCategory> category,
											  IngredientCategory fallback) {
		if (path == null || path.isEmpty()) {
			return fallback;
		}
		for (IngredientCategory candidate : category) {
			if (candidate.matchesPath(path)) {
				return candidate;
			}
		}
		return fallback;
	}
}
