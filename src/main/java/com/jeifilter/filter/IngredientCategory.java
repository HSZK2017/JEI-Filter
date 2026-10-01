package com.jeifilter.filter;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PotionItem;

/**
 * A selectable "kind of thing" that cuts across mods: potions, enchanted books, arrows.
 *
 * <p>These exist because the two interesting axes for a player are independent. "Which mod" answers
 * <em>who added this</em>; "which category" answers <em>what kind of thing is it</em>. A pack with
 * twenty mods that each add potions is tedious to clean up mod by mod, and impossible to clean up
 * when the potions hide inside a mod whose other items you want.
 *
 * <h2>How an item is classified</h2>
 *
 * <p>By its <strong>class</strong> first, and only then by its registry name. The class is the real
 * answer: mods that add potions subclass {@code PotionItem} and mods that add arrows subclass
 * {@code ArrowItem}, and those checks cover the base vanilla items too, because every potion in the
 * game <em>is</em> an instance of {@code PotionItem}. Verified against a 324-mod pack with
 * {@code javap}: {@code CustomPotionItem extends PotionItem}, {@code CustomSplashPotion extends
 * CustomPotionItem}, {@code ItemDragonArrow extends ArrowItem}, {@code ItemModArrow extends
 * ArrowItem}, {@code BurrowingArrowItem extends ArrowItem}, {@code BrewArrowItem extends ArrowItem}.
 *
 * <p>The registry name is the fallback, for items that are clearly one of these things but do not
 * subclass the base — goety's {@code undeath_potion} item extends plain {@code Item}. Relying on the
 * name alone would also be wrong: {@code tacz:potion_magazine} is a gun magazine and
 * {@code somemod:arrow_quiver} is a quiver, so the keyword has to be a whole trailing word.
 *
 * <p>The creator mod id is deliberately <em>not</em> used here — that is attribution, a separate axis
 * handled by {@link ModAttribution}.
 *
 * <p>The ordering lives in {@link ModClassifier}, which is free of Minecraft types so it can be unit
 * tested. The class predicates live in {@link ItemClassChecks} for the same reason: a lambda written
 * here would compile to a method on this enum, and merely loading the enum would then require
 * Minecraft — which the unit-test classpath does not have.
 */
public enum IngredientCategory {
	/**
	 * Everything that is not one of the categories below: a mod's ordinary items, and every non-item
	 * ingredient such as fluids. Always present, and it cannot be turned off by itself.
	 */
	MAIN("jei_filter.category.other", null),

	/** Potions, splash potions and lingering potions, by any mod. */
	POTION("jei_filter.category.potion", "potion"),

	/** Arrows, including tipped, spectral and modded arrows. */
	ARROW("jei_filter.category.arrow", "arrow"),

	/** Enchanted books, by any mod. */
	ENCHANTED_BOOK("jei_filter.category.enchanted_book", "enchanted_book");

	/** The categories a player can tick, i.e. everything except {@link #MAIN}. */
	public static final List<IngredientCategory> SELECTABLE = List.of(POTION, ARROW, ENCHANTED_BOOK);

	private final String translationKey;
	private final String keyword;

	IngredientCategory(String translationKey, String keyword) {
		this.translationKey = translationKey;
		this.keyword = keyword;
	}

	/** The id used in the saved config; stable across renames of the enum constant. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String translationKey() {
		return this.translationKey;
	}

	/** True for the categories a player may tick, as opposed to {@link #MAIN}. */
	public boolean isSelectable() {
		return this.keyword != null;
	}

	/** True when this item is an instance of the category's base type. */
	public boolean isOfClass(Item item) {
		Predicate<Item> check = ItemClassChecks.INSTANCE.get(this);
		return check != null && check.test(item);
	}

	public static IngredientCategory byId(String id) {
		for (IngredientCategory category : values()) {
			if (category.id().equalsIgnoreCase(id)) {
				return category;
			}
		}
		return null;
	}

	/**
	 * The category an item belongs to, or {@link #MAIN} when none applies.
	 *
	 * <p>Class first, then registry name; the ordering lives in {@link ModClassifier} so it is unit
	 * tested. A null item means the caller has no item (a fluid, or another mod's custom ingredient
	 * type), and only the name rule can apply.
	 */
	public static IngredientCategory of(@Nullable Item item, @Nullable ResourceLocation name) {
		return ModClassifier.attribute(item,
			value -> candidate -> candidate.isOfClass(value),
			value -> name == null ? null : name.getPath(),
			SELECTABLE,
			MAIN);
	}

	/** Convenience for callers that only have the item. */
	public static IngredientCategory of(@Nullable Item item) {
		return of(item, null);
	}

	/**
	 * The name-based rule on its own, free of Minecraft types so it is unit tested directly.
	 *
	 * <p>Every pattern requires the keyword to be a whole trailing segment, so {@code splash_potion}
	 * matches but {@code potion_magazine} does not.
	 */
	public static IngredientCategory classify(String path) {
		return ModClassifier.classify(path, SELECTABLE, MAIN);
	}

	/** True when this category claims a registry path by name. */
	public boolean matchesPath(String path) {
		return switch (this) {
			case POTION -> endsWithWord(path, "potion");
			case ARROW -> endsWithWord(path, "arrow");
			case ENCHANTED_BOOK -> path.equals("enchanted_book") || path.endsWith("_enchanted_book");
			case MAIN -> false;
		};
	}

	/**
	 * True when {@code path} is {@code word} or ends with {@code _word}, so {@code splash_potion}
	 * matches {@code potion} but {@code potion_magazine} does not.
	 */
	private static boolean endsWithWord(String path, String word) {
		if (path.equals(word)) {
			return true;
		}
		return path.length() > word.length() + 1 && path.endsWith("_" + word);
	}

	/**
	 * The class predicates, in a separate class on purpose: see the class comment. Resolved the first
	 * time {@link IngredientCategory#isOfClass} runs, which only ever happens in a running game.
	 */
	private static final class ItemClassChecks {
		private static final Map<IngredientCategory, Predicate<Item>> INSTANCE = build();

		private ItemClassChecks() {
		}

		private static Map<IngredientCategory, Predicate<Item>> build() {
			Map<IngredientCategory, Predicate<Item>> checks = new EnumMap<>(IngredientCategory.class);
			checks.put(IngredientCategory.POTION, item -> item instanceof PotionItem);
			checks.put(IngredientCategory.ARROW, item -> item instanceof ArrowItem);
			checks.put(IngredientCategory.ENCHANTED_BOOK, item -> item instanceof EnchantedBookItem);
			return Map.copyOf(checks);
		}
	}
}
