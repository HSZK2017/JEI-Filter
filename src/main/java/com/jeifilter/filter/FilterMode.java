package com.jeifilter.filter;

/**
 * How the checked mods in the filter screen are interpreted.
 */
public enum FilterMode {
	/**
	 * Checked mods are hidden from JEI. Nothing is checked by default,
	 * so JEI shows everything until the player opts in.
	 */
	BLACKLIST("blacklist"),

	/**
	 * Only the checked mods are shown in JEI; every other mod is hidden.
	 * Everything is checked by default, so JEI shows everything until the player opts out.
	 */
	WHITELIST("whitelist");

	private final String id;

	FilterMode(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public FilterMode next() {
		return this == BLACKLIST ? WHITELIST : BLACKLIST;
	}

	public static FilterMode byId(String id) {
		for (FilterMode mode : values()) {
			if (mode.id.equalsIgnoreCase(id)) {
				return mode;
			}
		}
		return BLACKLIST;
	}
}
