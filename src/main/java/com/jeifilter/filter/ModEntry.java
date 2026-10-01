package com.jeifilter.filter;

import java.util.Comparator;
import java.util.Locale;

/**
 * One entry in the filter screen's mod list.
 *
 * @param modId     the namespace of the ingredients, e.g. {@code minecraft} or {@code create}
 * @param modName   the human readable mod name reported by JEI
 * @param itemCount how many ingredients in JEI belong to this mod
 * @param kind      used only for sorting/grouping in the UI
 */
public record ModEntry(String modId, String modName, int itemCount, Kind kind) {
	/**
	 * The order these are declared in is the order the menu groups them in, because
	 * {@link #BY_NAME} sorts on {@link Enum#ordinal()}.
	 */
	public enum Kind {
		/** The vanilla namespace. Always sorted first. */
		MINECRAFT,
		/** JEI itself, right after vanilla. Hiding it is allowed but unusual. */
		JEI,
		/** Every other mod, sorted by display name. */
		MOD
	}

	/** The default order: Minecraft, then JEI, then every other mod by display name. */
	public static final Comparator<ModEntry> BY_NAME =
		Comparator.comparingInt((ModEntry entry) -> entry.kind().ordinal())
			.thenComparing(ModEntry::modName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(ModEntry::modId);

	/** Sorted by how many ingredients each mod contributes, so the big mods are easy to find. */
	public static final Comparator<ModEntry> BY_ITEM_COUNT =
		Comparator.comparingInt(ModEntry::itemCount)
			.reversed()
			.thenComparing(ModEntry::modName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(ModEntry::modId);

	/** Sorted alphabetically by mod id. */
	public static final Comparator<ModEntry> BY_MOD_ID =
		Comparator.comparing(ModEntry::modId, String.CASE_INSENSITIVE_ORDER);

	/** The two orders the screen can toggle between. */
	public enum Sort {
		NAME(BY_NAME),
		COUNT(BY_ITEM_COUNT),
		MOD_ID(BY_MOD_ID);

		private final Comparator<ModEntry> comparator;

		Sort(Comparator<ModEntry> comparator) {
			this.comparator = comparator;
		}

		public Comparator<ModEntry> comparator() {
			return this.comparator;
		}

		public Sort next() {
			Sort[] values = values();
			return values[(ordinal() + 1) % values.length];
		}
	}

	/** True when this entry matches the screen's search box text. */
	public boolean matches(String query) {
		if (query.isEmpty()) {
			return true;
		}
		String lowered = query.toLowerCase(Locale.ROOT);
		return modId.toLowerCase(Locale.ROOT).contains(lowered) ||
			modName.toLowerCase(Locale.ROOT).contains(lowered);
	}
}
