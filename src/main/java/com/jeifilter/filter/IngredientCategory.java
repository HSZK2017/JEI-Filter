package com.jeifilter.filter;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

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
 * <h2>Classification is by class, and only by class</h2>
 *
 * <p>An item belongs to a category when it <strong>is an instance of</strong> that category's vanilla
 * base type: {@code PotionItem}, {@code ArrowItem}, {@code EnchantedBookItem}. Nothing else is
 * consulted — not the registry name, not the translation key, not the tooltip.
 *
 * <p>This covers vanilla for free, because every potion in the game <em>is</em> a {@code PotionItem},
 * and it covers mods properly because mods subclass the base. Verified with {@code javap} against a
 * 324-mod pack:
 *
 * <pre>
 *   soulslike-weaponry  CustomPotionItem     extends PotionItem
 *   soulslike-weaponry  CustomSplashPotion   extends CustomPotionItem
 *   iceandfire          ItemDragonArrow      extends ArrowItem
 *   alexsmobs           ItemModArrow         extends ArrowItem
 *   alexscaves          BurrowingArrowItem   extends ArrowItem
 *   goety               BrewArrowItem        extends ArrowItem
 * </pre>
 *
 * <h2>Why there is no name matching</h2>
 *
 * <p>An earlier version ALSO matched the registry name, to catch items that are one of these things
 * without subclassing the base. That is gone: a name is a guess, and in a real pack the guesses are
 * wrong often enough to matter. Items whose names contain "potion" or "arrow" but which are not
 * potions or arrows exist in quantity — a gun magazine, a quiver, an arrowhead, decorative potion
 * blocks and bottles that are never drunk. Since hiding a category hides everything in it, every false
 * positive silently removes something the player never chose to hide.
 *
 * <p>The cost is real and accepted: something that behaves like a potion but extends plain
 * {@code Item} — goety's {@code undeath_potion} is one — is not a potion here and lands in
 * {@link #MAIN}. That is the safe direction. An item that stays visible is a mild annoyance; an item
 * that vanishes without being asked is a bug.
 */
public enum IngredientCategory {
	/**
	 * Everything that is not one of the categories below: a mod's ordinary items, and every non-item
	 * ingredient such as fluids. Always present, and it cannot be turned off by itself.
	 */
	MAIN("jei_filter.category.other"),

	/** Potions, splash potions and lingering potions, by any mod. */
	POTION("jei_filter.category.potion"),

	/** Arrows, including tipped, spectral and modded arrows. */
	ARROW("jei_filter.category.arrow"),

	/** Enchanted books, by any mod. */
	ENCHANTED_BOOK("jei_filter.category.enchanted_book");

	/** The categories a player can tick, i.e. everything except {@link #MAIN}. */
	public static final List<IngredientCategory> SELECTABLE = List.of(POTION, ARROW, ENCHANTED_BOOK);

	private final String translationKey;

	IngredientCategory(String translationKey) {
		this.translationKey = translationKey;
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
		return this != MAIN;
	}

	/** True when this item is an instance of the category's vanilla base type. */
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
	 * The category an item belongs to, or {@link #MAIN} when it is not an instance of any base type.
	 *
	 * <p>A null item — a fluid, or another mod's custom ingredient type — is always {@link #MAIN},
	 * because these categories are defined over items.
	 */
	public static IngredientCategory of(@Nullable Item item) {
		if (item != null) {
			for (IngredientCategory category : SELECTABLE) {
				if (category.isOfClass(item)) {
					return category;
				}
			}
		}
		return MAIN;
	}

	/**
	 * The class predicates, in a separate class on purpose.
	 *
	 * <p>A lambda written inside {@link IngredientCategory} compiles to a private method <em>on that
	 * class</em>, so its constant pool would name {@code PotionItem} and merely loading the enum would
	 * need Minecraft present. The unit tests run with no Minecraft at all, so the predicates live here
	 * and are resolved only when {@link #isOfClass} is called — which only happens in a running game.
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
