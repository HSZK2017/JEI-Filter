package com.jeifilter.jei;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jeifilter.JeiFilterMod;
import com.jeifilter.client.JeiFilterButton;
import com.jeifilter.filter.FilterMode;
import com.jeifilter.filter.FilterOptions;
import com.jeifilter.filter.ModEntry;
import com.mojang.logging.LogUtils;

import mezz.jei.api.constants.ModIds;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * Owns the mod filter state and drives JEI's ingredient visibility API.
 *
 * <p>All mutation methods must be called on the client main thread: JEI's
 * {@code IIngredientVisibility} asserts the main thread.
 */
public final class JeiFilterService implements IIngredientManager.IIngredientListener {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = JeiFilterMod.MOD_ID + ".json";

	private static final JeiFilterService INSTANCE = new JeiFilterService();

	public static JeiFilterService get() {
		return INSTANCE;
	}

	@Nullable
	private IJeiRuntime runtime;
	/** Decides between JEI's 15.55.0 visibility API and the older removeIngredientsAtRuntime. */
	@Nullable
	private JeiVisibilityBridge visibilityBridge;

	/** Everything JEI is showing, grouped by mod, across every registered ingredient type. */
	private ModCatalog catalog = ModCatalog.EMPTY;

	/** The mod ids this mod is currently hiding from JEI. */
	private Set<String> hiddenModIds = Set.of();
	/** True while this mod is itself telling JEI to hide or show ingredients. */
	private boolean applyingVisibility;
	/**
	 * True when JEI's ingredient list changed in a way we could not safely re-read yet, because
	 * hiding on this JEI version removes ingredients from that very list.
	 */
	private boolean catalogStale;
	/** True once JEI's ingredient list has been read successfully at least once. */
	private boolean ready = false;

	private Path configFile;
	private FilterOptions options = FilterOptions.EMPTY;

	private JeiFilterService() {
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	/**
	 * Called by the JEI plugin when JEI's runtime becomes available, which happens again after every
	 * world load and after an F3+T resource reload. JEI builds a fresh ingredient manager on each of
	 * those, so the mod list and the filter have to be re-applied every time.
	 */
	public void onRuntimeAvailable(IJeiRuntime runtime, Path configDir) {
		if (this.runtime != null && this.runtime != runtime) {
			LOGGER.warn("jei_filter: JEI runtime was replaced without an unload notification; re-attaching");
		}
		// Everything derived from the previous runtime is void: JEI builds a new ingredient manager
		// (and, on the older route, a new removed-ingredient set) on every start. Forgetting the old
		// hidden set here also stops the "catalog looks incomplete" check below from comparing a
		// fresh, still-loading catalog against the previous runtime's and rejecting it.
		boolean sameRuntime = this.runtime == runtime;
		this.runtime = runtime;
		if (!sameRuntime) {
			this.hiddenModIds = Set.of();
			this.catalog = ModCatalog.EMPTY;
		}
		this.catalogStale = false;
		this.configFile = configDir.resolve(FILE_NAME);
		this.options = loadOptions();

		IIngredientManager ingredientManager = runtime.getIngredientManager();
		ingredientManager.registerIngredientListener(this);
		// Detect once whether this JEI has the 15.55.0 visibility API or only the older
		// removeIngredientsAtRuntime route. Deliberately reflective: see JeiVisibilityBridge.
		this.visibilityBridge = JeiVisibilityBridge.create(ingredientManager,
			runtime.getJeiHelpers().getIngredientVisibility());

		// The hopper button needs a JEI drawable for the icon, which only exists once JEI is up.
		JeiFilterButton.getOrCreate(runtime.getJeiHelpers().getGuiHelper());

		rebuildCatalog(ingredientManager, runtime.getJeiHelpers().getModIdHelper());
		applyNow();
	}

	public void onRuntimeUnavailable() {
		// JEI clears its hidden-ingredient state when the runtime stops, so forget what we hid.
		this.runtime = null;
		this.visibilityBridge = null;
		this.ready = false;
		this.hiddenModIds = Set.of();
		this.catalogStale = false;
	}

	// ------------------------------------------------------------------
	// queries used by the GUI
	// ------------------------------------------------------------------

	/** True while JEI is up and its ingredient list has been read. */
	public boolean isReady() {
		return this.ready && this.runtime != null;
	}

	public FilterOptions options() {
		return this.options;
	}

	/** Every mod that currently has at least one ingredient in JEI, in display order. */
	public List<ModEntry> modEntries() {
		refreshCatalogIfStale();
		return this.catalog.entries();
	}

	/** The number of mods that are currently hidden from JEI. */
	public int hiddenModCount() {
		return this.hiddenModIds.size();
	}

	/** The number of ingredients currently hidden from JEI, across every ingredient type. */
	public int hiddenIngredientCount() {
		return countIngredients(this.hiddenModIds);
	}

	private int countIngredients(Collection<String> modIds) {
		int total = 0;
		for (List<Object> ingredients : this.catalog.ingredientsOf(modIds).values()) {
			total += ingredients.size();
		}
		return total;
	}

	public Set<String> hiddenModIds() {
		return this.hiddenModIds;
	}

	public boolean isFilterActive() {
		return !this.hiddenModIds.isEmpty();
	}

	public Optional<IJeiRuntime> runtime() {
		return Optional.ofNullable(this.runtime);
	}

	// ------------------------------------------------------------------
	// mutations
	// ------------------------------------------------------------------

	public void setOptions(FilterOptions newOptions) {
		if (this.options.equals(newOptions)) {
			return;
		}
		this.options = newOptions;
		// The catalog is what the hide/unhide plan is computed from, so it has to be current.
		refreshCatalogIfStale();
		// Apply immediately so the player sees each checkbox take effect in JEI right away,
		// then persist it so the filter survives a restart.
		applyNow();
		saveOptions();
	}

	public void toggleMod(String modId) {
		setOptions(this.options.withToggled(modId));
	}

	public void setSelected(Collection<String> modIds, boolean selected) {
		setOptions(this.options.withSelection(modIds, selected));
	}

	public void invertSelection(Collection<String> modIds) {
		setOptions(this.options.withInvertedSelection(modIds));
	}

	public void setMode(FilterMode mode) {
		setOptions(this.options.withMode(mode));
	}

	// ------------------------------------------------------------------
	// internals
	// ------------------------------------------------------------------

	/**
	 * Rebuilds the mod catalog from every ingredient JEI is showing, of every registered ingredient
	 * type. Only call from the client main thread.
	 *
	 * <p>An empty result means JEI has not finished loading its ingredients, so it is treated as
	 * "not ready yet" rather than as "no mods", and the caller retries when JEI reports changes.
	 *
	 * @return true if the filter may be re-applied against this catalog
	 */
	public boolean rebuildCatalog(IIngredientManager ingredientManager, IModIdHelper modIdHelper) {
		ModCatalog rebuilt = ModCatalog.build(ingredientManager, modIdHelper);

		if (rebuilt.isEmpty()) {
			this.ready = false;
			return false;
		}

		// A catalog that does not contain every mod the previous one had is treated as incomplete
		// rather than as authoritative: in WHITELIST mode "not in the catalog" means "hidden", so
		// applying a partial list would briefly hide mods the player whitelisted. Keep the previous
		// catalog and pick up the complete one on the next ingredient change.
		Set<String> previous = this.catalog.loadedModIds();
		if (!previous.isEmpty() && !rebuilt.loadedModIds().containsAll(previous)) {
			Set<String> missing = new LinkedHashSet<>(previous);
			missing.removeAll(rebuilt.loadedModIds());
			LOGGER.warn("jei_filter: JEI's ingredient list is missing {} mods that were present before ({}); "
					+ "keeping the previous filter until it is complete", missing.size(), missing);
			return false;
		}

		// On a JEI whose hiding takes ingredients out of the list it reports, a catalog read while a
		// filter is active is a filtered catalog. A shrunken one is therefore almost certainly an
		// artefact of reading at the wrong moment, not a mod the player removed, and replacing the
		// good catalog with it would lose track of which mods are currently hidden.
		boolean hidesByRemoval = this.visibilityBridge != null && !this.visibilityBridge.hidesRecipeSlots();
		if (hidesByRemoval && !this.hiddenModIds.isEmpty()
			&& rebuilt.loadedModIds().size() < previous.size()) {
			LOGGER.warn("jei_filter: refusing a catalog of {} mods while filtering with a previous catalog of {}; "
					+ "it was read while ingredients were hidden",
				rebuilt.loadedModIds().size(), previous.size());
			return false;
		}

		this.catalog = rebuilt;
		this.ready = true;
		return true;
	}

	private void applyNow() {
		if (this.runtime == null) {
			return;
		}
		JeiVisibilityBridge bridge = this.visibilityBridge;
		if (bridge == null || this.applyingVisibility) {
			return;
		}

		FilterOptions.FilterPlan plan = this.options.plan(this.hiddenModIds, this.catalog.loadedModIds());

		// The legacy JEI route (addIngredientsAtRuntime / removeIngredientsAtRuntime) notifies
		// ingredient listeners synchronously, and this mod is one of them, so without this guard
		// applyNow -> addIngredientsAtRuntime -> onIngredientsAdded -> refreshFromIngredients ->
		// applyNow recurses until the stack overflows. Nothing this mod does to JEI should be
		// treated as news about JEI's ingredient list.
		this.applyingVisibility = true;
		try {
			// Unhide first, then hide. The decision of which mods go where lives in FilterOptions#plan
			// so it is covered by unit tests; getting the sense of these two calls backwards produces
			// a filter that does the opposite of its label.
			applyVisibility(bridge, plan.modsToUnhide(), true);
			applyVisibility(bridge, plan.modsToHide(), false);
		} finally {
			this.applyingVisibility = false;
		}
		this.hiddenModIds = plan.newHiddenSet();
		LOGGER.info("jei_filter: hiding {} mods ({} ingredients) from JEI", this.hiddenModIds.size(),
			countIngredients(this.hiddenModIds));
	}

	/**
	 * Hands JEI every ingredient of the named mods — items, fluids, and anything another mod
	 * registered as its own ingredient type — and asks it to hide or show them.
	 */
	private void applyVisibility(JeiVisibilityBridge bridge, Set<String> modIds, boolean visible) {
		if (modIds.isEmpty()) {
			return;
		}
		bridge.setVisible(this.catalog.ingredientsOf(modIds), visible);
	}

	// ------------------------------------------------------------------
	// JEI listeners
	// ------------------------------------------------------------------

	/**
	 * JEI's ingredient list changed, so the mod catalog and therefore the hidden set can be stale.
	 * This is also what recovers from JEI reporting an empty ingredient list the first time around.
	 */
	@Override
	public <V> void onIngredientsAdded(mezz.jei.api.ingredients.IIngredientHelper<V> ingredientHelper,
									   Collection<ITypedIngredient<V>> ingredients) {
		refreshFromIngredients();
	}

	@Override
	public <V> void onIngredientsRemoved(mezz.jei.api.ingredients.IIngredientHelper<V> ingredientHelper,
										 Collection<ITypedIngredient<V>> ingredients) {
		refreshFromIngredients();
	}

	/**
	 * JEI's ingredient list changed.
	 *
	 * <p>Re-applying immediately is only safe when reading JEI's list back gives a complete picture.
	 * That holds on JEI 15.55.0+, where hiding is a visibility flag and {@code getAllIngredients}
	 * still returns everything. It does <strong>not</strong> hold on older JEI, where the only
	 * public route ({@code removeIngredientsAtRuntime}) actually takes the ingredients out of the
	 * list: rebuilding then would read back the filtered list, conclude that the hidden mods are
	 * simply not installed, and unhide them. That is exactly the "the items came back after a
	 * moment" bug.
	 *
	 * <p>So on the older route the catalog is kept and only re-read at a deliberate point — when the
	 * player opens the menu or changes the selection — via {@link #refreshCatalogIfStale()} and
	 * {@link #refreshCatalogNow(String)}.
	 */
	private void refreshFromIngredients() {
		if (this.applyingVisibility) {
			// Our own change; JEI is echoing it back at us.
			return;
		}
		IJeiRuntime currentRuntime = this.runtime;
		if (currentRuntime == null) {
			return;
		}

		if (this.visibilityBridge != null && !this.visibilityBridge.hidesRecipeSlots()) {
			this.catalogStale = true;
			return;
		}

		if (rebuildCatalog(currentRuntime.getIngredientManager(),
			currentRuntime.getJeiHelpers().getModIdHelper())) {
			applyNow();
		}
	}

	private void refreshCatalogIfStale() {
		if (this.catalogStale && !this.applyingVisibility) {
			refreshCatalogNow("catalog was marked stale");
		}
	}

	/**
	 * Un-hides everything this mod hid, re-reads JEI's now-complete ingredient list, and re-applies
	 * the selection. Used on the older JEI route, where the ingredient list cannot be read while a
	 * filter is active.
	 *
	 * <p>The whole sequence holds {@code applyingVisibility}, which makes it atomic with respect to
	 * {@link #applyNow} and to itself. That matters: this method both un-hides and re-hides, and if
	 * an {@code applyNow} could interleave with it, the bookkeeping would be updated against a
	 * half-applied state and the filter would end up hiding the wrong mods.
	 *
	 * @return true if the catalog was rebuilt
	 */
	public boolean refreshCatalogNow(String reason) {
		IJeiRuntime currentRuntime = this.runtime;
		JeiVisibilityBridge bridge = this.visibilityBridge;
		if (currentRuntime == null || bridge == null || this.applyingVisibility) {
			return false;
		}

		this.applyingVisibility = true;
		try {
			if (!this.hiddenModIds.isEmpty()) {
				LOGGER.info("jei_filter: re-reading JEI's ingredients ({})", reason);
				// Take our own hides back off first, so JEI's list is complete again while we read it.
				bridge.setVisible(this.catalog.ingredientsOf(this.hiddenModIds), true);
				this.hiddenModIds = Set.of();
			}

			if (!rebuildCatalog(currentRuntime.getIngredientManager(),
				currentRuntime.getJeiHelpers().getModIdHelper())) {
				return false;
			}
			this.catalogStale = false;
			applyNow();
			return true;
		} finally {
			this.applyingVisibility = false;
		}
	}

	// ------------------------------------------------------------------
	// persistence
	// ------------------------------------------------------------------

	private FilterOptions loadOptions() {
		Path file = this.configFile;
		if (file == null || !Files.isRegularFile(file)) {
			return FilterOptions.EMPTY;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			FilterOptions loaded = FilterOptions.fromJson(root);
			LOGGER.info("jei_filter: loaded filter settings from {}", file);
			return loaded;
		} catch (IOException | RuntimeException e) {
			LOGGER.error("jei_filter: could not read {}; starting with no filter", file, e);
			return FilterOptions.EMPTY;
		}
	}

	private void saveOptions() {
		Path file = this.configFile;
		if (file == null) {
			return;
		}
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			JsonObject root = new JsonObject();
			root.addProperty("_comment", "Mods checked in JEI's filter button menu, and what checked means.");
			root.add("options", this.options.toJson());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.error("jei_filter: could not save {}; the filter will not persist", file, e);
		}
	}

	/** For tests: parses the on-disk shape. */
	public static FilterOptions parseConfig(JsonElement root) {
		if (root != null && root.isJsonObject() && root.getAsJsonObject().has("options")) {
			return FilterOptions.fromJson(root.getAsJsonObject().get("options"));
		}
		return FilterOptions.fromJson(root);
	}
}
