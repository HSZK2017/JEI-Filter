package com.jeifilter.filter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The persisted player selection, on two axes: which mods, and which categories of thing.
 *
 * <h2>One rule</h2>
 *
 * <p><strong>A ticked box means "show it".</strong> Nothing is hidden unless the player unticks it, and
 * a box never means the opposite of what it looks like. There is no mode whose checkboxes mean "hide"
 * instead — that is how the two levels got out of step before.
 *
 * <h2>Two sets, and why</h2>
 *
 * <p>Ticking a mod shows everything that mod has, so an item is visible when its mod is ticked, or
 * when the box for its category under that mod is ticked. That much is one line. The subtlety is
 * unticking a <em>single category</em> of a ticked mod: the mod's tick must keep showing the mod's
 * other categories, so "this one category is off" cannot be represented by clearing the mod's tick.
 *
 * <p>So the off-switch is its own set:
 *
 * <ul>
 *   <li>{@link #ticked()} — mods, and mod-plus-category boxes, that are ticked.</li>
 *   <li>{@link #untickedCategories()} — individual categories explicitly turned off. It only has an
 *       effect while their mod is still ticked, and ticking the mod again clears them.</li>
 * </ul>
 *
 * <pre>
 *   shown(mod, category) = (ticked(mod) and not unticked(mod|category))
 *                          or ticked(mod|category)
 * </pre>
 *
 * <p>That expression is the whole model. Everything below either reads it or maintains the two sets so
 * it stays true.
 *
 * <p>Immutable; every {@code with*} method returns a new instance.
 */
public final class FilterOptions {
	/** Separates the mod id from the category id inside a facet key. */
	private static final char FACET_SEPARATOR = '|';

	/** The default: nothing ticked. A fresh install ticks every loaded mod instead; see the service. */
	public static final FilterOptions EMPTY = new FilterOptions(Set.of(), Set.of());

	private final Set<String> ticked;
	private final Set<String> untickedCategories;

	private FilterOptions(Set<String> ticked, Set<String> untickedCategories) {
		this.ticked = Set.copyOf(ticked);
		this.untickedCategories = Set.copyOf(untickedCategories);
	}

	public static FilterOptions of(Collection<String> ticked, Collection<String> untickedCategories) {
		return new FilterOptions(new LinkedHashSet<>(ticked), new LinkedHashSet<>(untickedCategories));
	}

	// ------------------------------------------------------------------
	// facets
	// ------------------------------------------------------------------

	public static String facet(String modId, IngredientCategory category) {
		return modId + FACET_SEPARATOR + category.id();
	}

	/** The mod a facet refers to, or null when the key is malformed. */
	@Nullable
	public static String facetModId(String facetKey) {
		int index = facetKey.indexOf(FACET_SEPARATOR);
		return index <= 0 ? null : facetKey.substring(0, index);
	}

	/** The category a facet refers to, or null when the key is malformed or unknown. */
	@Nullable
	public static IngredientCategory facetCategory(String facetKey) {
		int index = facetKey.indexOf(FACET_SEPARATOR);
		if (index <= 0 || index == facetKey.length() - 1) {
			return null;
		}
		return IngredientCategory.byId(facetKey.substring(index + 1));
	}

	/** Every ticked box: whole mods, and mod-plus-category boxes. */
	public Set<String> ticked() {
		return this.ticked;
	}

	/** Categories explicitly turned off, which only matter while their mod is still ticked. */
	public Set<String> untickedCategories() {
		return this.untickedCategories;
	}

	// ------------------------------------------------------------------
	// queries
	// ------------------------------------------------------------------

	/** Whether the mod's own box is ticked, which shows everything that mod has. */
	public boolean isModTicked(String modId) {
		return this.ticked.contains(modId);
	}

	/** Whether one category-within-a-mod box is ticked. */
	public boolean isCategoryTicked(String modId, IngredientCategory category) {
		return this.ticked.contains(facet(modId, category));
	}

	/** Whether one category-within-a-mod box has been turned off while its mod stays ticked. */
	public boolean isCategoryUnticked(String modId, IngredientCategory category) {
		return this.untickedCategories.contains(facet(modId, category));
	}

	/**
	 * Whether this mod's category is shown. This is the rule from the class comment, and the only
	 * place visibility is decided.
	 */
	public boolean isCategoryVisible(String modId, IngredientCategory category) {
		return (isModTicked(modId) && !isCategoryUnticked(modId, category))
			|| isCategoryTicked(modId, category);
	}

	/**
	 * The tick state of one mod's row: ticked when the whole mod is, empty when nothing of it is,
	 * mixed when only some of its categories are.
	 */
	public TickState modState(String modId, Collection<IngredientCategory> categories) {
		if (categories.isEmpty()) {
			return isModTicked(modId) ? TickState.ALL : TickState.NONE;
		}
		int visible = 0;
		for (IngredientCategory category : categories) {
			if (isCategoryVisible(modId, category)) {
				visible++;
			}
		}
		if (visible == 0) {
			return TickState.NONE;
		}
		return visible == categories.size() ? TickState.ALL : TickState.SOME;
	}

	/**
	 * The tick state of a category's row, across every mod that has that category: ticked when every
	 * one of them shows it, empty when none do, mixed in between.
	 *
	 * <p>A pure aggregate of the sub-boxes — that is what lets the category box act as a master switch
	 * without becoming a second source of truth.
	 */
	public TickState categoryState(IngredientCategory category, Collection<String> modIds) {
		if (modIds.isEmpty()) {
			return TickState.NONE;
		}
		int visible = 0;
		for (String modId : modIds) {
			if (isCategoryVisible(modId, category)) {
				visible++;
			}
		}
		if (visible == 0) {
			return TickState.NONE;
		}
		return visible == modIds.size() ? TickState.ALL : TickState.SOME;
	}

	// ------------------------------------------------------------------
	// mutation
	// ------------------------------------------------------------------

	/**
	 * Ticks or unticks one whole mod.
	 *
	 * <p>Ticking clears that mod's category exceptions, so it shows everything. Unticking drops the
	 * mod's own facet and its per-category boxes, so nothing of it is left ticked.
	 */
	public FilterOptions withModTicked(String modId, boolean ticked, Collection<IngredientCategory> categories) {
		Set<String> nextTicked = new LinkedHashSet<>(this.ticked);
		Set<String> nextUnticked = new LinkedHashSet<>(this.untickedCategories);
		nextTicked.remove(modId);
		for (IngredientCategory category : categories) {
			nextTicked.remove(facet(modId, category));
			nextUnticked.remove(facet(modId, category));
		}
		if (ticked) {
			nextTicked.add(modId);
		}
		return new FilterOptions(nextTicked, nextUnticked);
	}

	/**
	 * Ticks or unticks one category of one mod.
	 *
	 * <p>Unticking records an exception rather than clearing the mod's tick, because the mod's tick is
	 * what keeps its <em>other</em> categories visible. Clearing it here would hide the whole mod,
	 * which is not what unticking one category means.
	 */
	public FilterOptions withCategoryTicked(String modId, IngredientCategory category, boolean ticked) {
		String facet = facet(modId, category);
		Set<String> nextTicked = new LinkedHashSet<>(this.ticked);
		Set<String> nextUnticked = new LinkedHashSet<>(this.untickedCategories);
		if (ticked) {
			nextTicked.add(facet);
			nextUnticked.remove(facet);
		} else {
			nextTicked.remove(facet);
			if (nextTicked.contains(modId)) {
				nextUnticked.add(facet);
			} else {
				nextUnticked.remove(facet);
			}
		}
		return new FilterOptions(nextTicked, nextUnticked);
	}

	/** Ticks or unticks one category across many mods — the master box on a category row. */
	public FilterOptions withCategoryTickedEverywhere(IngredientCategory category, Collection<String> modIds,
													  boolean ticked) {
		if (modIds.isEmpty()) {
			return this;
		}
		Set<String> nextTicked = new LinkedHashSet<>(this.ticked);
		Set<String> nextUnticked = new LinkedHashSet<>(this.untickedCategories);
		for (String modId : modIds) {
			String facet = facet(modId, category);
			if (ticked) {
				nextTicked.add(facet);
				nextUnticked.remove(facet);
			} else {
				nextTicked.remove(facet);
				if (nextTicked.contains(modId)) {
					nextUnticked.add(facet);
				} else {
					nextUnticked.remove(facet);
				}
			}
		}
		return new FilterOptions(nextTicked, nextUnticked);
	}

	/**
	 * Ticks or unticks a category on every mod in {@code categoriesByMod}, which is how a mod row's
	 * sub-boxes are written in bulk.
	 */
	public FilterOptions withCategoryTickedEverywhere(IngredientCategory category,
													  Map<String, ? extends Collection<IngredientCategory>> categoriesByMod,
													  boolean ticked) {
		return withCategoryTickedEverywhere(category, categoriesByMod.keySet(), ticked);
	}

	/** Ticks or unticks many mods as whole mods. This is what All / None do. */
	public FilterOptions withModsTicked(Collection<String> modIds, Collection<IngredientCategory> categories,
										boolean ticked) {
		FilterOptions result = this;
		for (String modId : modIds) {
			result = result.withModTicked(modId, ticked, categories);
		}
		return result;
	}

	/** Flips the tick of every listed mod's whole-mod box. */
	public FilterOptions withInverted(Collection<String> modIds, Collection<IngredientCategory> categories) {
		Set<String> nextTicked = new LinkedHashSet<>(this.ticked);
		Set<String> nextUnticked = new LinkedHashSet<>(this.untickedCategories);
		boolean changed = false;
		for (String modId : modIds) {
			changed = true;
			if (nextTicked.remove(modId)) {
				// It was shown as a whole mod; now nothing of it is.
				for (IngredientCategory category : categories) {
					nextTicked.remove(facet(modId, category));
					nextUnticked.remove(facet(modId, category));
				}
			} else {
				nextTicked.add(modId);
				for (IngredientCategory category : categories) {
					nextUnticked.remove(facet(modId, category));
				}
			}
		}
		return changed ? new FilterOptions(nextTicked, nextUnticked) : this;
	}

	/**
	 * Ticks every mod not seen before, so a mod that loads after the config was written is shown
	 * rather than silently missing.
	 *
	 * <p>Only the mod's own facet is written. Ticking a mod already means "show everything it has", so
	 * adding a facet per category as well would be redundant and would make the saved config noisy.
	 */
	public FilterOptions withNewModsTicked(Collection<String> knownModIds, Collection<String> loadedModIds) {
		Set<String> nextTicked = new LinkedHashSet<>(this.ticked);
		boolean changed = false;
		for (String modId : loadedModIds) {
			if (!knownModIds.contains(modId)) {
				nextTicked.add(modId);
				changed = true;
			}
		}
		return changed ? new FilterOptions(nextTicked, this.untickedCategories) : this;
	}

	// ------------------------------------------------------------------
	// the hide/unhide plan
	// ------------------------------------------------------------------

	/** One ingredient, identified the way JEI identifies it. */
	public record IngredientKey(String namespace, String path) {
		@Override
		public String toString() {
			return namespace + ":" + path;
		}
	}

	/** Where an ingredient sits: which mod added it, and what kind of thing it is. */
	public record IngredientInfo(String modId, IngredientCategory category) {
	}

	/**
	 * Which ingredients are hidden: exactly those that are not shown.
	 *
	 * <p>The unit is the ingredient, not the mod, because a category is not expressible as a set of
	 * mods — hiding "every potion" has to leave the rest of each mod's items alone.
	 */
	public Set<IngredientKey> hiddenIngredients(Map<IngredientKey, IngredientInfo> ingredientsByKey) {
		Set<IngredientKey> hidden = new LinkedHashSet<>();
		for (Map.Entry<IngredientKey, IngredientInfo> entry : ingredientsByKey.entrySet()) {
			IngredientInfo info = entry.getValue();
			if (!isCategoryVisible(info.modId(), info.category())) {
				hidden.add(entry.getKey());
			}
		}
		return hidden;
	}

	/**
	 * The transition from {@code currentlyHidden} to this selection.
	 *
	 * @param ingredientsByKey every known ingredient, with the mod and category it was filed under
	 */
	public IngredientPlan plan(Collection<IngredientKey> currentlyHidden,
							   Map<IngredientKey, IngredientInfo> ingredientsByKey) {
		Set<IngredientKey> target = hiddenIngredients(ingredientsByKey);
		Set<IngredientKey> toUnhide = new LinkedHashSet<>(currentlyHidden);
		toUnhide.removeAll(target);
		Set<IngredientKey> toHide = new LinkedHashSet<>(target);
		toHide.removeAll(currentlyHidden);
		return new IngredientPlan(toUnhide, toHide, target);
	}

	/**
	 * What {@link #plan} decided.
	 *
	 * @param toUnhide     ingredients to hand to JEI's {@code unhideIngredients} (they become visible)
	 * @param toHide       ingredients to hand to JEI's {@code hideIngredients} (they become invisible)
	 * @param newHiddenSet the value to remember as "currently hidden"
	 */
	public record IngredientPlan(Set<IngredientKey> toUnhide, Set<IngredientKey> toHide,
								 Set<IngredientKey> newHiddenSet) {
	}

	/** The tick state drawn in a checkbox. */
	public enum TickState {
		/** Empty box: nothing under this row is ticked. */
		NONE,
		/** Square box: some of what is under this row is ticked, some is not. */
		SOME,
		/** Ticked box: everything under this row is ticked. */
		ALL
	}

	// ------------------------------------------------------------------
	// persistence
	// ------------------------------------------------------------------

	/** Parses options from a JSON object, tolerating any malformed shape. */
	public static FilterOptions fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return EMPTY;
		}
		return parse(element.getAsJsonObject());
	}

	private static FilterOptions parse(JsonObject object) {
		Set<String> ticked = readStrings(object, "ticked");
		Set<String> unticked = readStrings(object, "unticked");
		if (ticked.isEmpty()) {
			// Formats written before the category work. All of them held whole-mod keys, which are
			// still whole-mod facets, so they migrate as-is.
			ticked = readStrings(object, "tickedMods");
			if (ticked.isEmpty()) {
				ticked = readStrings(object, "selected");
			}
		}
		return new FilterOptions(ticked, unticked);
	}

	private static Set<String> readStrings(JsonObject object, String key) {
		Set<String> values = new LinkedHashSet<>();
		if (!object.has(key) || !object.get(key).isJsonArray()) {
			return values;
		}
		JsonArray array = object.getAsJsonArray(key);
		for (JsonElement entry : array) {
			if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
				String value = entry.getAsString();
				if (!value.isBlank()) {
					values.add(value);
				}
			}
		}
		return values;
	}

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		object.add("ticked", toArray(this.ticked));
		if (!this.untickedCategories.isEmpty()) {
			object.add("unticked", toArray(this.untickedCategories));
		}
		return object;
	}

	private static JsonArray toArray(Set<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(new JsonPrimitive(value));
		}
		return array;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		return other instanceof FilterOptions that
			&& ticked.equals(that.ticked)
			&& untickedCategories.equals(that.untickedCategories);
	}

	@Override
	public int hashCode() {
		return 31 * ticked.hashCode() + untickedCategories.hashCode();
	}

	@Override
	public String toString() {
		return "FilterOptions[ticked=" + ticked.size() + ", untickedCategories=" + untickedCategories.size() + "]";
	}
}
