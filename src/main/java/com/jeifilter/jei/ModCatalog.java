package com.jeifilter.jei;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;

import com.jeifilter.filter.ModAttribution;
import com.jeifilter.filter.ModEntry;
import com.mojang.logging.LogUtils;

import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;

/**
 * A snapshot of everything JEI is showing, grouped by the mod that registered it.
 *
 * <p>JEI's ingredient list is not just items: fluids, and anything another mod registers as its own
 * {@link IIngredientType}, are separate types with their own helpers. Filtering "hide this mod"
 * therefore has to walk every registered type, not just {@code VanillaTypes.ITEM_STACK} — otherwise
 * a hidden mod's fluids and custom ingredients stay on screen and in whitelist mode a fully hidden
 * pack still shows fluids.
 *
 * <p>The mod id for an ingredient comes from the namespace of its
 * {@link IIngredientHelper#getResourceLocation(Object) registry name}, which every ingredient type
 * has to provide. The display name comes from JEI's {@link IModIdHelper}.
 *
 * <p>Immutable. Built once from the ingredient manager and replaced wholesale on a rebuild.
 */
final class ModCatalog {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** The empty catalog, used before JEI has reported any ingredients. */
	static final ModCatalog EMPTY = new ModCatalog(Map.of(), List.of(), Set.of());

	/** modId -> the ingredients of that mod, per ingredient type. */
	private final Map<String, Map<IIngredientType<?>, List<Object>>> byMod;
	private final List<ModEntry> entries;
	private final Set<String> loadedModIds;

	private ModCatalog(Map<String, Map<IIngredientType<?>, List<Object>>> byMod,
					   List<ModEntry> entries,
					   Set<String> loadedModIds) {
		this.byMod = byMod;
		this.entries = entries;
		this.loadedModIds = loadedModIds;
	}

	/**
	 * Reads every ingredient of every registered type out of JEI and groups it by mod.
	 *
	 * @return the catalog, or {@link #EMPTY} if JEI has not reported its ingredients yet
	 */
	static ModCatalog build(IIngredientManager ingredientManager, IModIdHelper modIdHelper) {
		Map<String, Map<IIngredientType<?>, List<Object>>> byMod = new TreeMap<>();
		Map<String, Map<IIngredientType<?>, Integer>> countsByMod = new TreeMap<>();
		int total = 0;
		int skippedTypes = 0;

		for (IIngredientType<?> type : ingredientManager.getRegisteredIngredientTypes()) {
			int forType = collectType(ingredientManager, type, byMod, countsByMod);
			if (forType < 0) {
				skippedTypes++;
			} else {
				total += forType;
			}
		}

		if (byMod.isEmpty()) {
			LOGGER.warn("jei_filter: JEI reported no ingredients yet; the mod list will be built on the next change");
			return EMPTY;
		}

		List<ModEntry> entries = new ArrayList<>(byMod.size());
		for (Map.Entry<String, Map<IIngredientType<?>, Integer>> entry : countsByMod.entrySet()) {
			String modId = entry.getKey();
			int count = 0;
			for (int value : entry.getValue().values()) {
				count += value;
			}
			entries.add(new ModEntry(modId, displayName(modId, modIdHelper), count, classify(modId)));
		}
		entries.sort(ModEntry.BY_NAME);

		if (skippedTypes > 0) {
			LOGGER.warn("jei_filter: {} JEI ingredient type(s) could not be read and are not filtered", skippedTypes);
		}
		LOGGER.info("jei_filter: JEI exposes {} ingredients from {} mods across {} ingredient type(s)",
			total, byMod.size(), ingredientManager.getRegisteredIngredientTypes().size());

		return new ModCatalog(
			Collections.unmodifiableMap(byMod),
			List.copyOf(entries),
			Collections.unmodifiableSet(new LinkedHashSet<>(byMod.keySet())));
	}

	/**
	 * @return the number of ingredients read for this type, or -1 if JEI refused to hand them over
	 */
	private static int collectType(IIngredientManager ingredientManager, IIngredientType<?> type,
								   Map<String, Map<IIngredientType<?>, List<Object>>> byMod,
								   Map<String, Map<IIngredientType<?>, Integer>> countsByMod) {
		Collection<?> ingredients;
		try {
			ingredients = ingredientManager.getAllIngredients(type);
		} catch (RuntimeException e) {
			LOGGER.warn("jei_filter: could not read JEI ingredients of type {}; that type will not be filtered",
				type.getIngredientClass().getName(), e);
			return -1;
		}
		if (ingredients == null || ingredients.isEmpty()) {
			return 0;
		}

		// One helper per type; looking it up per ingredient is needlessly expensive.
		IIngredientHelper<Object> helper = helperFor(ingredientManager, type);
		if (helper == null) {
			return -1;
		}

		int count = 0;
		for (Object ingredient : ingredients) {
			String modId = modIdOf(helper, ingredient);
			if (modId == null) {
				continue;
			}
			byMod.computeIfAbsent(modId, key -> new LinkedHashMap<>())
				.computeIfAbsent(type, key -> new ArrayList<>())
				.add(ingredient);
			// Not a TreeMap: IIngredientType is not Comparable.
			countsByMod.computeIfAbsent(modId, key -> new LinkedHashMap<>())
				.merge(type, 1, Integer::sum);
			count++;
		}
		return count;
	}

	@SuppressWarnings("unchecked")
	private static IIngredientHelper<Object> helperFor(IIngredientManager ingredientManager, IIngredientType<?> type) {
		try {
			return (IIngredientHelper<Object>) ingredientManager.getIngredientHelper(type);
		} catch (RuntimeException e) {
			LOGGER.warn("jei_filter: JEI has no usable ingredient helper for {}; that type will not be filtered",
				type.getIngredientClass().getName(), e);
			return null;
		}
	}

	/**
	 * Which mod an ingredient belongs to, using JEI's own attribution.
	 *
	 * <p>The precedence rule lives in {@link ModAttribution} so it is unit tested; this method just
	 * supplies the two inputs. Reading the registry namespace directly was the bug that made modded
	 * potions and enchanted books count as {@code minecraft}: their registry names really are
	 * {@code minecraft:potion} and {@code minecraft:enchanted_book}, and only the creator-mod-id
	 * lookup identifies the mod that actually added them.
	 */
	private static String modIdOf(IIngredientHelper<Object> helper, Object ingredient) {
		try {
			if (!helper.isValidIngredient(ingredient)) {
				return null;
			}
			return ModAttribution.attribute(ingredient, helper::getDisplayModId,
				value -> helper.getResourceLocation(value).getNamespace());
		} catch (RuntimeException e) {
			// A single bad ingredient must not take the whole catalog down.
			return null;
		}
	}

	private static ModEntry.Kind classify(String modId) {
		if (mezz.jei.api.constants.ModIds.MINECRAFT_ID.equals(modId)) {
			return ModEntry.Kind.MINECRAFT;
		}
		if (mezz.jei.api.constants.ModIds.JEI_ID.equals(modId)) {
			return ModEntry.Kind.JEI;
		}
		return ModEntry.Kind.MOD;
	}

	private static String displayName(String modId, IModIdHelper modIdHelper) {
		if (modIdHelper != null) {
			try {
				String name = modIdHelper.getModNameForModId(modId);
				if (name != null && !name.isBlank()) {
					return name;
				}
			} catch (RuntimeException e) {
				LOGGER.debug("jei_filter: JEI could not resolve a display name for {}", modId, e);
			}
		}
		return net.minecraftforge.fml.ModList.get()
			.getModContainerById(modId)
			.map(container -> container.getModInfo().getDisplayName())
			.orElse(modId);
	}

	// ------------------------------------------------------------------
	// queries
	// ------------------------------------------------------------------

	boolean isEmpty() {
		return this.entries.isEmpty();
	}

	List<ModEntry> entries() {
		return this.entries;
	}

	Set<String> loadedModIds() {
		return this.loadedModIds;
	}

	/**
	 * Every ingredient belonging to the named mods, keyed by ingredient type, ready to hand to JEI.
	 */
	Map<IIngredientType<?>, List<Object>> ingredientsOf(Collection<String> modIds) {
		Map<IIngredientType<?>, List<Object>> result = new LinkedHashMap<>();
		for (String modId : modIds) {
			Map<IIngredientType<?>, List<Object>> perType = this.byMod.get(modId);
			if (perType == null) {
				continue;
			}
			for (Map.Entry<IIngredientType<?>, List<Object>> entry : perType.entrySet()) {
				result.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
			}
		}
		return result;
	}
}
