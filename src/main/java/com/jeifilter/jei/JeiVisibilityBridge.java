package com.jeifilter.jei;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;

/**
 * Hides and shows ingredient groups through whichever JEI API the running version actually has.
 *
 * <p>JEI grew a dedicated runtime visibility API in <strong>15.55.0</strong>
 * ({@code IIngredientVisibility#hideIngredients}/{@code #unhideIngredients} with a set of
 * {@code UidContext}s). Before that, the public way to do the same thing was
 * {@code IIngredientManager#removeIngredientsAtRuntime} / {@code #addIngredientsAtRuntime}.
 *
 * <p>Both routes end in the same JEI internals: {@code IngredientVisibility#setIngredientsVisible}
 * calls {@code IngredientBlacklistInternal#onIngredientsVisibilityChanged}, and
 * {@code IngredientManager#removeIngredientsAtRuntime} calls
 * {@code IngredientBlacklistInternal#onIngredientsRemoved}. The newer one can also target recipe
 * slots and catalysts ({@code UidContext.Recipe}); the older one affects the ingredient list, which
 * is what this mod's menu is about.
 *
 * <p>The new API is reached by reflection on purpose, so the mod does not hard-link to a method that
 * only exists in newer JEI. Compiling against the oldest supported JEI is what keeps it loadable on
 * the older packs.
 */
final class JeiVisibilityBridge {
	private static final Logger LOGGER = LogUtils.getLogger();

	private final IIngredientManager ingredientManager;
	private final Object visibility;
	private final Method hideIngredients;
	private final Method unhideIngredients;

	private JeiVisibilityBridge(IIngredientManager ingredientManager, Object visibility,
								Method hideIngredients, Method unhideIngredients) {
		this.ingredientManager = ingredientManager;
		this.visibility = visibility;
		this.hideIngredients = hideIngredients;
		this.unhideIngredients = unhideIngredients;
	}

	/**
	 * Detects once, when JEI becomes available, which API this JEI offers.
	 */
	static JeiVisibilityBridge create(IIngredientManager ingredientManager, Object visibility) {
		if (visibility != null) {
			Method hide = find(visibility.getClass(), "hideIngredients");
			Method unhide = find(visibility.getClass(), "unhideIngredients");
			if (hide != null && unhide != null) {
				LOGGER.info("jei_filter: using JEI's runtime visibility API (hideIngredients/unhideIngredients)");
				return new JeiVisibilityBridge(ingredientManager, visibility, hide, unhide);
			}
		}
		LOGGER.info("jei_filter: this JEI has no hideIngredients/unhideIngredients (added in JEI 15.55.0); "
			+ "falling back to removeIngredientsAtRuntime, which hides the same ingredients from the list");
		return new JeiVisibilityBridge(ingredientManager, null, null, null);
	}

	private static Method find(Class<?> type, String name) {
		for (Method method : type.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == 3) {
				method.setAccessible(true);
				return method;
			}
		}
		return null;
	}

	/** True when recipe slots and catalysts are hidden too, not just the ingredient list. */
	boolean hidesRecipeSlots() {
		return this.hideIngredients != null;
	}

	/**
	 * @param visible true to make the ingredients available again, false to hide them
	 * @param ingredientsByType every ingredient of the affected mods, grouped by ingredient type, so
	 *                          items, fluids and any other registered type are all covered
	 */
	void setVisible(Map<IIngredientType<?>, List<Object>> ingredientsByType, boolean visible) {
		for (Map.Entry<IIngredientType<?>, List<Object>> entry : ingredientsByType.entrySet()) {
			setVisible(entry.getKey(), entry.getValue(), visible);
		}
	}

	private <V> void setVisible(IIngredientType<V> ingredientType, List<Object> ingredients, boolean visible) {
		if (ingredients.isEmpty()) {
			return;
		}
		@SuppressWarnings("unchecked")
		Collection<V> typed = (Collection<V>) ingredients;
		if (this.hideIngredients == null) {
			legacySetVisible(ingredientType, typed, visible);
			return;
		}
		try {
			// hideIngredients / unhideIngredients(ingredientType, ingredients, contexts)
			Object contexts = List.of(
				mezz.jei.api.ingredients.subtypes.UidContext.Ingredient,
				mezz.jei.api.ingredients.subtypes.UidContext.Recipe);
			(visible ? this.unhideIngredients : this.hideIngredients)
				.invoke(this.visibility, ingredientType, typed, contexts);
		} catch (IllegalAccessException | InvocationTargetException e) {
			LOGGER.error("jei_filter: JEI rejected a {} call for {} {}", visible ? "unhide" : "hide",
				typed.size(), ingredientType.getIngredientClass().getSimpleName(), e);
		}
	}

	private <V> void legacySetVisible(IIngredientType<V> ingredientType, Collection<V> ingredients, boolean visible) {
		try {
			if (visible) {
				this.ingredientManager.addIngredientsAtRuntime(ingredientType, ingredients);
			} else {
				this.ingredientManager.removeIngredientsAtRuntime(ingredientType, ingredients);
			}
		} catch (RuntimeException e) {
			LOGGER.error("jei_filter: JEI rejected a {} call for {} {}", visible ? "add" : "remove",
				ingredients.size(), ingredientType.getIngredientClass().getSimpleName(), e);
		}
	}
}
