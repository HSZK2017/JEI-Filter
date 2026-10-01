package com.jeifilter.filter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The persisted player selection: which mods are checked, and what "checked" means.
 *
 * <p>Immutable. {@link #withToggled(String)}, {@link #withSelection(Collection, boolean)} and
 * {@link #withMode(FilterMode)} return new instances.
 */
public final class FilterOptions {
	public static final FilterOptions EMPTY = new FilterOptions(FilterMode.BLACKLIST, Set.of());

	private final FilterMode mode;
	private final Set<String> selectedModIds;

	private FilterOptions(FilterMode mode, Set<String> selectedModIds) {
		this.mode = mode;
		this.selectedModIds = Set.copyOf(selectedModIds);
	}

	public static FilterOptions of(FilterMode mode, Collection<String> selectedModIds) {
		return new FilterOptions(mode, new LinkedHashSet<>(selectedModIds));
	}

	/**
	 * Parses options from a JSON object, tolerating any malformed shape.
	 * Returns {@link #EMPTY} when the input is unusable.
	 */
	public static FilterOptions fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return EMPTY;
		}
		JsonObject object = element.getAsJsonObject();
		FilterMode mode = FilterMode.BLACKLIST;
		if (object.has("mode") && object.get("mode").isJsonPrimitive()) {
			mode = FilterMode.byId(object.get("mode").getAsString());
		}
		Set<String> ids = new LinkedHashSet<>();
		if (object.has("selected") && object.get("selected").isJsonArray()) {
			JsonArray array = object.getAsJsonArray("selected");
			for (JsonElement entry : array) {
				if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
					String id = entry.getAsString();
					if (!id.isBlank()) {
						ids.add(id);
					}
				}
			}
		}
		return new FilterOptions(mode, ids);
	}

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		object.addProperty("mode", mode.getId());
		JsonArray array = new JsonArray();
		for (String id : selectedModIds) {
			array.add(new JsonPrimitive(id));
		}
		object.add("selected", array);
		return object;
	}

	public FilterMode mode() {
		return mode;
	}

	/** Every checked mod id, regardless of whether that mod is currently loaded. */
	public Set<String> selectedModIds() {
		return selectedModIds;
	}

	public boolean isSelected(String modId) {
		return selectedModIds.contains(modId);
	}

	public boolean isEmpty() {
		return selectedModIds.isEmpty() && mode == FilterMode.BLACKLIST;
	}

	public int selectionCount() {
		return selectedModIds.size();
	}

	public FilterOptions withMode(FilterMode newMode) {
		if (newMode == this.mode) {
			return this;
		}
		return new FilterOptions(newMode, this.selectedModIds);
	}

	public FilterOptions withToggled(String modId) {
		Set<String> next = new LinkedHashSet<>(this.selectedModIds);
		if (!next.remove(modId)) {
			next.add(modId);
		}
		return new FilterOptions(this.mode, next);
	}

	public FilterOptions withSelection(Collection<String> modIds, boolean selected) {
		Set<String> next = new LinkedHashSet<>(this.selectedModIds);
		if (selected) {
			next.addAll(modIds);
		} else {
			next.removeAll(modIds);
		}
		return new FilterOptions(this.mode, next);
	}

	public FilterOptions withInvertedSelection(Collection<String> modIds) {
		Set<String> next = new LinkedHashSet<>(this.selectedModIds);
		for (String modId : modIds) {
			if (!next.remove(modId)) {
				next.add(modId);
			}
		}
		return new FilterOptions(this.mode, next);
	}

	/**
	 * The set of mods that must be hidden from JEI for this selection.
	 *
	 * @param loadedModIds the mod ids that currently have at least one ingredient in JEI
	 */
	public Set<String> hiddenModIds(Collection<String> loadedModIds) {
		Set<String> hidden = new LinkedHashSet<>();
		for (String modId : loadedModIds) {
			if (shouldHide(modId)) {
				hidden.add(modId);
			}
		}
		return hidden;
	}

	public boolean shouldHide(String modId) {
		return switch (mode) {
			case BLACKLIST -> selectedModIds.contains(modId);
			case WHITELIST -> !selectedModIds.contains(modId);
		};
	}

	/**
	 * The transition from {@code currentlyHidden} to this selection, as the sequence of steps JEI
	 * must be told about.
	 *
	 * <p>Unhiding the stale mods comes first so that a mod which moves between "should be hidden" and
	 * "should be visible" is never left hidden by an earlier call, and mods that are no longer in
	 * {@code loadedModIds} (JEI dropped them) are still released.
	 *
	 * <p>This is a pure function of the selection so the hide/unhide decision can be unit tested:
	 * getting {@code visible} backwards here produces a filter that does exactly the opposite of its
	 * label, which is invisible to any test that only looks at {@link #hiddenModIds}.
	 */
	public FilterPlan plan(Collection<String> currentlyHidden, Collection<String> loadedModIds) {
		Set<String> hidden = new LinkedHashSet<>();
		Set<String> unhidden = new LinkedHashSet<>();
		Set<String> target = hiddenModIds(loadedModIds);

		for (String modId : currentlyHidden) {
			if (!target.contains(modId)) {
				unhidden.add(modId);
			}
		}
		for (String modId : target) {
			if (!currentlyHidden.contains(modId)) {
				hidden.add(modId);
			}
		}
		return new FilterPlan(unhidden, hidden, target);
	}

	/**
	 * What {@link #plan} decided.
	 *
	 * @param modsToUnhide mods to hand to JEI's {@code unhideIngredients} (they become visible)
	 * @param modsToHide   mods to hand to JEI's {@code hideIngredients} (they become invisible)
	 * @param newHiddenSet the value the caller should remember as "currently hidden"
	 */
	public record FilterPlan(Set<String> modsToUnhide, Set<String> modsToHide, Set<String> newHiddenSet) {
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof FilterOptions that)) {
			return false;
		}
		return mode == that.mode && selectedModIds.equals(that.selectedModIds);
	}

	@Override
	public int hashCode() {
		return 31 * mode.hashCode() + selectedModIds.hashCode();
	}

	@Override
	public String toString() {
		return "FilterOptions[mode=" + mode + ", selected=" + selectedModIds.size() + "]";
	}
}
