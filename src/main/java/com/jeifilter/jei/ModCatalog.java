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

import com.jeifilter.filter.FilterOptions;
import com.jeifilter.filter.IngredientCategory;
import com.jeifilter.filter.ModAttribution;
import com.jeifilter.filter.ModEntry;
import com.mojang.logging.LogUtils;

import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A snapshot of everything JEI is showing, grouped by the mod that added it and by what kind of
 * thing it is.
 *
 * <p>Two axes, both needed. JEI's ingredient list is not just items: fluids, and anything another
 * mod registers as its own {@link IIngredientType}, are separate types with their own helpers, so
 * hiding "this mod" has to walk every registered type or a hidden mod's fluids stay on screen. And
 * within a mod, the player may want only part of it — every potion but nothing else — which is what
 * {@link IngredientCategory} is for.
 *
 * <p>The mod id for an ingredient comes from {@link ModAttribution}, which follows JEI: creator mod
 * id first, registry namespace as the fallback. The category comes from the item's registry path.
 *
 * <p>Immutable. Built once from the ingredient manager and replaced wholesale on a rebuild.
 */
final class ModCatalog {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** The empty catalog, used before JEI has reported any ingredients. */
	static final ModCatalog EMPTY = new ModCatalog(Map.of(), List.of(), Set.of(), Map.of(), Map.of(), Map.of());

	/** modId -> category -> ingredient type -> the ingredients. */
	private final Map<String, Map<IngredientCategory, Map<IIngredientType<?>, List<Object>>>> byMod;
	private final List<ModEntry> entries;
	private final Set<String> loadedModIds;
	/** modId -> category -> how many ingredients. */
	private final Map<String, Map<IngredientCategory, Integer>> counts;
	/** Every ingredient's placement, keyed the way JEI identifies it. */
	private final Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> ingredientIndex;
	/** The reverse of the index, so a key can be turned back into an ingredient for JEI. */
	private final Map<FilterOptions.IngredientKey, Map.Entry<IIngredientType<?>, Object>> byKey;

	private ModCatalog(Map<String, Map<IngredientCategory, Map<IIngredientType<?>, List<Object>>>> byMod,
					   List<ModEntry> entries,
					   Set<String> loadedModIds,
					   Map<String, Map<IngredientCategory, Integer>> counts,
					   Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> ingredientIndex,
					   Map<FilterOptions.IngredientKey, Map.Entry<IIngredientType<?>, Object>> byKey) {
		this.byMod = byMod;
		this.entries = entries;
		this.loadedModIds = loadedModIds;
		this.counts = counts;
		this.ingredientIndex = ingredientIndex;
		this.byKey = byKey;
	}

	/**
	 * Reads every ingredient of every registered type out of JEI and groups it by mod and category.
	 *
	 * @return the catalog, or {@link EMPTY} if JEI has not reported its ingredients yet
	 */
	static ModCatalog build(IIngredientManager ingredientManager, IModIdHelper modIdHelper) {
		Map<String, Map<IngredientCategory, Map<IIngredientType<?>, List<Object>>>> byMod = new TreeMap<>();
		Map<String, Map<IngredientCategory, Integer>> counts = new TreeMap<>();
		Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> index = new LinkedHashMap<>();
		Map<FilterOptions.IngredientKey, Map.Entry<IIngredientType<?>, Object>> byKey = new LinkedHashMap<>();
		int total = 0;
		int skippedTypes = 0;

		for (IIngredientType<?> type : ingredientManager.getRegisteredIngredientTypes()) {
			int forType = collectType(ingredientManager, type, byMod, counts, index, byKey);
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
		for (Map.Entry<String, Map<IngredientCategory, Integer>> entry : counts.entrySet()) {
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
			Collections.unmodifiableSet(new LinkedHashSet<>(byMod.keySet())),
			Collections.unmodifiableMap(counts),
			Collections.unmodifiableMap(index),
			Collections.unmodifiableMap(byKey));
	}

	/**
	 * @return the number of ingredients read for this type, or -1 if JEI refused to hand them over
	 */
	private static int collectType(IIngredientManager ingredientManager, IIngredientType<?> type,
								   Map<String, Map<IngredientCategory, Map<IIngredientType<?>, List<Object>>>> byMod,
								   Map<String, Map<IngredientCategory, Integer>> counts,
								   Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> index,
								   Map<FilterOptions.IngredientKey, Map.Entry<IIngredientType<?>, Object>> byKey) {
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
			IngredientCategory category = categoryOf(ingredient);
			byMod.computeIfAbsent(modId, key -> new LinkedHashMap<>())
				.computeIfAbsent(category, key -> new LinkedHashMap<>())
				.computeIfAbsent(type, key -> new ArrayList<>())
				.add(ingredient);
			counts.computeIfAbsent(modId, key -> new LinkedHashMap<>())
				.merge(category, 1, Integer::sum);

			FilterOptions.IngredientKey key = keyOf(helper, ingredient);
			if (key != null) {
				index.putIfAbsent(key, new FilterOptions.IngredientInfo(modId, category));
				byKey.putIfAbsent(key, Map.entry(type, ingredient));
			}
			count++;
		}
		return count;
	}

	/**
	 * How one ingredient is identified: its registry name plus JEI's unique id, so subtypes of the
	 * same item (an enchanted book with different enchantments) stay distinct.
	 */
	private static FilterOptions.IngredientKey keyOf(IIngredientHelper<Object> helper, Object ingredient) {
		try {
			ResourceLocation location = helper.getResourceLocation(ingredient);
			if (location == null) {
				return null;
			}
			String uid = helper.getUniqueId(ingredient, UidContext.Ingredient);
			return new FilterOptions.IngredientKey(location.getNamespace(), location.getPath() + "#" + uid);
		} catch (RuntimeException e) {
			return null;
		}
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

	/**
	 * Which category an ingredient belongs to. Anything that is not an item is "main", because the
	 * categories are defined over items.
	 *
	 * <p>The registry name is passed along as well: the class is the primary answer, and the name is
	 * the fallback for items that are one of these things without subclassing the base — for example
	 * goety's {@code undeath_potion} item extends plain {@code Item}.
	 */
	private static IngredientCategory categoryOf(Object ingredient) {
		if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
			return IngredientCategory.MAIN;
		}
		return IngredientCategory.of(stack.getItem(), ForgeRegistries.ITEMS.getKey(stack.getItem()));
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

	/** Every category that mod has ingredients in, in the categories' own order. */
	Set<IngredientCategory> categoriesOf(String modId) {
		Map<IngredientCategory, Map<IIngredientType<?>, List<Object>>> perCategory = this.byMod.get(modId);
		if (perCategory == null || perCategory.isEmpty()) {
			return Set.of();
		}
		Set<IngredientCategory> present = new LinkedHashSet<>();
		for (IngredientCategory category : IngredientCategory.SELECTABLE) {
			if (perCategory.containsKey(category)) {
				present.add(category);
			}
		}
		// Everything that is not a special category, including non-item ingredients.
		if (perCategory.containsKey(IngredientCategory.MAIN)) {
			present.add(IngredientCategory.MAIN);
		}
		return present;
	}

	int countOf(String modId, IngredientCategory category) {
		return this.counts.getOrDefault(modId, Map.of()).getOrDefault(category, 0);
	}

	/** For each loaded mod, the categories it has ingredients in. */
	Map<String, Set<IngredientCategory>> categoriesByMod() {
		Map<String, Set<IngredientCategory>> result = new LinkedHashMap<>();
		for (String modId : this.loadedModIds) {
			result.put(modId, categoriesOf(modId));
		}
		return result;
	}

	/** How many loaded mods have ingredients in this category. */
	int modCountOf(IngredientCategory category) {
		int count = 0;
		for (String modId : this.loadedModIds) {
			if (this.byMod.getOrDefault(modId, Map.of()).containsKey(category)) {
				count++;
			}
		}
		return count;
	}

	/** The loaded mods that have ingredients in this category. */
	List<String> modsWith(IngredientCategory category) {
		List<String> mods = new ArrayList<>();
		for (String modId : this.loadedModIds) {
			if (this.byMod.getOrDefault(modId, Map.of()).containsKey(category)) {
				mods.add(modId);
			}
		}
		return mods;
	}

	int totalCountOf(IngredientCategory category) {
		int total = 0;
		for (String modId : this.loadedModIds) {
			total += countOf(modId, category);
		}
		return total;
	}

	/** Every ingredient belonging to the named mods, keyed by ingredient type. */
	Map<IIngredientType<?>, List<Object>> ingredientsOf(Collection<String> modIds) {
		return ingredientsOf(modIds, null);
	}

	/**
	 * Every ingredient belonging to the named mods, optionally restricted to one category.
	 *
	 * @param category the only category to include, or null for all of them
	 */
	Map<IIngredientType<?>, List<Object>> ingredientsOf(Collection<String> modIds,
														IngredientCategory category) {
		Map<IIngredientType<?>, List<Object>> result = new LinkedHashMap<>();
		for (String modId : modIds) {
			Map<IngredientCategory, Map<IIngredientType<?>, List<Object>>> perCategory = this.byMod.get(modId);
			if (perCategory == null) {
				continue;
			}
			for (Map.Entry<IngredientCategory, Map<IIngredientType<?>, List<Object>>> categoryEntry
				: perCategory.entrySet()) {
				if (category != null && categoryEntry.getKey() != category) {
					continue;
				}
				for (Map.Entry<IIngredientType<?>, List<Object>> typeEntry : categoryEntry.getValue().entrySet()) {
					result.computeIfAbsent(typeEntry.getKey(), key -> new ArrayList<>()).addAll(typeEntry.getValue());
				}
			}
		}
		return result;
	}

	// ------------------------------------------------------------------
	// ingredient-level lookups, for hiding part of a mod
	// ------------------------------------------------------------------

	/** Where every known ingredient sits, keyed the way JEI identifies it. */
	Map<FilterOptions.IngredientKey, FilterOptions.IngredientInfo> ingredientIndex() {
		return this.ingredientIndex;
	}

	/**
	 * The ingredients behind the given keys, grouped by ingredient type and ready for JEI.
	 *
	 * <p>Backed by a map built alongside the catalog rather than a scan, because this runs on every
	 * checkbox click and the catalog can hold tens of thousands of ingredients.
	 */
	Map<IIngredientType<?>, List<Object>> ingredientsFor(Collection<FilterOptions.IngredientKey> keys) {
		Map<IIngredientType<?>, List<Object>> result = new LinkedHashMap<>();
		for (FilterOptions.IngredientKey key : keys) {
			Map.Entry<IIngredientType<?>, Object> entry = this.byKey.get(key);
			if (entry != null) {
				result.computeIfAbsent(entry.getKey(), type -> new ArrayList<>()).add(entry.getValue());
			}
		}
		return result;
	}
}
