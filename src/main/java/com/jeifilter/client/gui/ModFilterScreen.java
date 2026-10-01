package com.jeifilter.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.jeifilter.filter.FilterOptions;
import com.jeifilter.filter.IngredientCategory;
import com.jeifilter.filter.ModEntry;
import com.jeifilter.jei.JeiFilterService;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The menu opened by the hopper button next to JEI's search bar.
 *
 * <p>Two levels. The top of the list holds one row per <em>category</em> — potions, arrows, enchanted
 * books — and below it one row per mod. A mod row expands into one sub-row per category that mod
 * actually has ingredients in, so a category can be turned off for one mod without losing the rest of
 * that mod's items.
 *
 * <p>All three checkboxes work on one shared truth: a facet is either ticked or not, and an
 * ingredient shows when its mod or its mod-plus-category is ticked. That is what makes a category's
 * master checkbox able to drive every mod's sub-checkbox for that category, and what lets a sub-row
 * override one mod afterwards.
 *
 * <p>Every change applies to JEI immediately, so the player can watch items disappear while the
 * screen is open, and is written to disk as it changes.
 */
@OnlyIn(Dist.CLIENT)
public final class ModFilterScreen extends Screen {
	private static final int MIN_WIDTH = 300;
	private static final int MAX_WIDTH = 520;
	private static final int MIN_HEIGHT = 160;
	private static final int MAX_HEIGHT = 300;
	private static final int PADDING = 8;
	private static final int ROW_HEIGHT = 14;
	private static final int WIDGET_HEIGHT = 18;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int COLOR_BACKGROUND = 0xC0101010;
	private static final int COLOR_BORDER = 0xFF808080;
	private static final int COLOR_ROW_HOVER = 0x40FFFFFF;
	private static final int COLOR_ROW_CURSOR = 0x30FFFFFF;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_TEXT_DIM = 0xFFA0A0A0;
	private static final int COLOR_TEXT_FAINT = 0xFF707070;
	private static final int COLOR_HEADER = 0xFFFFD479;
	private static final int COLOR_DIVIDER = 0x60FFFFFF;
	private static final int COLOR_TICK = 0xFF55FF55;
	private static final int COLOR_PARTIAL = 0xFFFFAA00;

	private final Screen parent;
	private final JeiFilterService service = JeiFilterService.get();

	private EditBox searchBox;
	private Button sortButton;
	private Button allButton;
	private Button noneButton;
	private Button invertButton;

	private String query = "";
	private ModEntry.Sort sort = ModEntry.Sort.NAME;
	private List<Row> rows = List.of();
	private int cursor = -1;
	private int scrollRow;
	/** Mods whose sub-rows are open. */
	private final Set<String> expanded = new LinkedHashSet<>();

	private int layoutLeft;
	private int layoutTop;
	private int layoutWidth;
	private int layoutHeight;
	private int hintY;
	private int listTop;
	private int listHeight;

	public ModFilterScreen(Screen parent) {
		super(Component.translatable("jei_filter.screen.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------
	// rows
	// ------------------------------------------------------------------

	/** One line of the list: a category, a mod, or one category under one mod. */
	private sealed interface Row {
		/** A row that can be ticked, and reports its own three-state tick. */
		sealed interface Tickable extends Row {
			FilterOptions.TickState tick();
		}

		/** A category's master row, covering every mod that has it. */
		record Category(IngredientCategory category, FilterOptions.TickState tick, int mods, int ingredients)
			implements Tickable {
		}

		/** A mod row, plus the handle that opens its sub-rows. */
		record Mod(ModEntry entry, FilterOptions.TickState tick, boolean open, List<IngredientCategory> subCategories)
			implements Tickable {
		}

		/** One category under one mod. */
		record Sub(String modId, IngredientCategory category, FilterOptions.TickState tick, int count)
			implements Tickable {
		}
	}

	private void rebuildRows() {
		List<Row> result = new ArrayList<>();

		for (IngredientCategory category : this.service.visibleCategories()) {
			if (matches(categoryName(category).getString())) {
				result.add(new Row.Category(category, this.service.categoryState(category),
					this.service.categoryModCount(category), this.service.categoryIngredientCount(category)));
			}
		}

		List<ModEntry> entries = new ArrayList<>();
		for (ModEntry entry : this.service.modEntries()) {
			if (this.query.isEmpty() || entry.matches(this.query)
				|| this.service.categoriesOf(entry.modId()).stream()
					.anyMatch(category -> matches(categoryName(category).getString()))) {
				entries.add(entry);
			}
		}
		entries.sort(this.sort.comparator());

		// While searching, open every mod so the player can see what matched.
		boolean querying = !this.query.isEmpty();
		for (ModEntry entry : entries) {
			List<IngredientCategory> categories = this.service.categoriesOf(entry.modId());
			boolean open = querying || this.expanded.contains(entry.modId());
			result.add(new Row.Mod(entry, this.service.modState(entry.modId()), open, categories));
			if (open) {
				for (IngredientCategory category : categories) {
					result.add(new Row.Sub(entry.modId(), category,
						this.service.isCategoryVisible(entry.modId(), category)
							? FilterOptions.TickState.ALL : FilterOptions.TickState.NONE,
						this.service.countOf(entry.modId(), category)));
				}
			}
		}

		this.rows = List.copyOf(result);
		this.cursor = Mth.clamp(this.cursor, -1, this.rows.size() - 1);
		clampScroll();
	}

	private boolean matches(String text) {
		return this.query.isEmpty() || text.toLowerCase(Locale.ROOT).contains(this.query);
	}

	private static Component categoryName(IngredientCategory category) {
		return Component.translatable(category.translationKey());
	}

	// ------------------------------------------------------------------
	// layout
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		int width = Mth.clamp(this.width - 20, MIN_WIDTH, MAX_WIDTH);
		int height = Mth.clamp(this.height - 40, MIN_HEIGHT, MAX_HEIGHT);
		this.layoutWidth = width;
		this.layoutHeight = height;
		this.layoutLeft = (this.width - width) / 2;
		this.layoutTop = (this.height - height) / 2;

		int contentLeft = this.layoutLeft + PADDING;
		int contentWidth = width - PADDING * 2;
		int y = this.layoutTop + PADDING + 23;
		this.hintY = this.layoutTop + PADDING + 12;

		this.sortButton = Button.builder(sortLabel(), b -> {
			this.sort = this.sort.next();
			rebuildRows();
			refreshWidgets();
		}).pos(contentLeft, y).size(contentWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.sortButton);

		y += WIDGET_HEIGHT + 4;

		int helperWidth = 44;
		int searchWidth = contentWidth - (helperWidth + 2) * 3 - 2;
		this.searchBox = new EditBox(this.font, contentLeft, y, searchWidth, WIDGET_HEIGHT,
			Component.translatable("jei_filter.search.hint"));
		this.searchBox.setHint(Component.translatable("jei_filter.search.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.searchBox.setResponder(text -> {
			this.query = text.trim().toLowerCase(Locale.ROOT);
			rebuildRows();
			this.scrollRow = 0;
			this.cursor = -1;
		});
		this.searchBox.setValue(this.query);
		addRenderableWidget(this.searchBox);

		int bx = contentLeft + searchWidth + 2;
		this.allButton = Button.builder(Component.translatable("jei_filter.button.all"), b -> setAllTicked(true))
			.pos(bx, y).size(helperWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.allButton);
		bx += helperWidth + 2;
		this.noneButton = Button.builder(Component.translatable("jei_filter.button.none"), b -> setAllTicked(false))
			.pos(bx, y).size(helperWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.noneButton);
		bx += helperWidth + 2;
		this.invertButton = Button.builder(Component.translatable("jei_filter.button.invert"), b -> invertShown())
			.pos(bx, y).size(helperWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.invertButton);

		y += WIDGET_HEIGHT + 4;
		this.listTop = y;
		this.listHeight = Math.max(ROW_HEIGHT,
			this.layoutTop + height - PADDING - 12 - WIDGET_HEIGHT - 6 - y);

		int footerY = this.layoutTop + height - PADDING - WIDGET_HEIGHT;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
			.pos(this.layoutLeft + width - PADDING - 100, footerY)
			.size(100, WIDGET_HEIGHT)
			.build());

		rebuildRows();
		refreshWidgets();
	}

	private Component sortLabel() {
		return Component.translatable("jei_filter.button.sort",
			Component.translatable("jei_filter.sort." + this.sort.name().toLowerCase(Locale.ROOT)));
	}

	private void refreshWidgets() {
		boolean empty = this.rows.isEmpty();
		if (this.allButton != null) {
			this.allButton.active = !empty;
		}
		if (this.noneButton != null) {
			this.noneButton.active = !empty;
		}
		if (this.invertButton != null) {
			this.invertButton.active = !empty;
		}
		if (this.sortButton != null) {
			this.sortButton.setMessage(sortLabel());
		}
	}

	// ------------------------------------------------------------------
	// selection actions
	// ------------------------------------------------------------------

	/** The mods currently listed, which is what the bulk buttons act on. */
	private List<String> listedModIds() {
		Set<String> mods = new LinkedHashSet<>();
		for (Row row : this.rows) {
			if (row instanceof Row.Mod mod) {
				mods.add(mod.entry().modId());
			} else if (row instanceof Row.Sub sub) {
				mods.add(sub.modId());
			}
		}
		return List.copyOf(mods);
	}

	private void setAllTicked(boolean ticked) {
		List<String> mods = listedModIds();
		if (!mods.isEmpty()) {
			this.service.setModsTicked(mods, ticked);
			rebuildRows();
		}
	}

	private void invertShown() {
		List<String> mods = listedModIds();
		if (!mods.isEmpty()) {
			this.service.invertSelection(mods);
			rebuildRows();
		}
	}

	/** A category's master checkbox: drives every mod that has that category. */
	private void toggleCategory(IngredientCategory category) {
		FilterOptions.TickState state = this.service.categoryState(category);
		this.service.setCategoryTickedEverywhere(category, state != FilterOptions.TickState.ALL);
		rebuildRows();
	}

	private void toggleMod(Row.Mod mod) {
		this.service.setModTicked(mod.entry().modId(), mod.tick() != FilterOptions.TickState.ALL);
		rebuildRows();
	}

	private void toggleSub(Row.Sub sub) {
		this.service.setCategoryTicked(sub.modId(), sub.category(), sub.tick() != FilterOptions.TickState.ALL);
		rebuildRows();
	}

	private void toggleRow(int index) {
		if (index < 0 || index >= this.rows.size()) {
			return;
		}
		Row row = this.rows.get(index);
		if (row instanceof Row.Category category) {
			toggleCategory(category.category());
		} else if (row instanceof Row.Mod mod) {
			// A plain click still hides the whole mod; the disclosure arrow is what opens sub-rows.
			toggleMod(mod);
		} else if (row instanceof Row.Sub sub) {
			toggleSub(sub);
		}
		refreshWidgets();
	}

	private void toggleExpanded(Row.Mod mod) {
		String modId = mod.entry().modId();
		if (!this.expanded.remove(modId)) {
			this.expanded.add(modId);
		}
		rebuildRows();
	}

	// ------------------------------------------------------------------
	// scrolling
	// ------------------------------------------------------------------

	private int maxScrollRow() {
		int visibleRows = Math.max(1, this.listHeight / ROW_HEIGHT);
		return Math.max(0, this.rows.size() - visibleRows);
	}

	private void clampScroll() {
		this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScrollRow());
	}

	private void ensureVisible(int index) {
		if (index < 0) {
			return;
		}
		int visibleRows = Math.max(1, this.listHeight / ROW_HEIGHT);
		if (index < this.scrollRow) {
			this.scrollRow = index;
		} else if (index >= this.scrollRow + visibleRows) {
			this.scrollRow = index - visibleRows + 1;
		}
		clampScroll();
	}

	private int rowAt(double mouseY) {
		if (mouseY < this.listTop || mouseY >= this.listTop + this.listHeight) {
			return -1;
		}
		int index = this.scrollRow + (int) ((mouseY - this.listTop) / ROW_HEIGHT);
		return index >= 0 && index < this.rows.size() ? index : -1;
	}

	private int rowTop(int index) {
		return this.listTop + (index - this.scrollRow) * ROW_HEIGHT;
	}

	// ------------------------------------------------------------------
	// rendering
	// ------------------------------------------------------------------

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics);
		renderFrame(guiGraphics);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		renderList(guiGraphics, mouseX, mouseY);
		renderFooter(guiGraphics);
	}

	private void renderFrame(GuiGraphics guiGraphics) {
		guiGraphics.fill(this.layoutLeft, this.layoutTop,
			this.layoutLeft + this.layoutWidth, this.layoutTop + this.layoutHeight, COLOR_BACKGROUND);
		guiGraphics.renderOutline(this.layoutLeft, this.layoutTop, this.layoutWidth, this.layoutHeight, COLOR_BORDER);
		guiGraphics.drawString(this.font, this.title,
			this.layoutLeft + PADDING, this.layoutTop + PADDING, COLOR_TEXT, false);
	}

	private void renderFooter(GuiGraphics guiGraphics) {
		int left = this.layoutLeft + PADDING;
		int right = this.layoutLeft + this.layoutWidth - PADDING;
		int y = this.layoutTop + this.layoutHeight - PADDING - 10;
		guiGraphics.fill(left, y - 3, right, y - 2, COLOR_DIVIDER);

		Component status = this.service.isReady()
			? Component.translatable("jei_filter.status.summary",
				this.service.hiddenIngredientCount(), this.service.tickedModCount())
			: Component.translatable("jei_filter.status.not_ready").withStyle(ChatFormatting.RED);
		guiGraphics.drawString(this.font, status, left, y, COLOR_TEXT_DIM, false);

		guiGraphics.drawString(this.font, Component.translatable("jei_filter.status.hint"),
			this.layoutLeft + PADDING, this.hintY, COLOR_TEXT_FAINT, false);
	}

	private void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		int left = this.layoutLeft + PADDING;
		int right = this.layoutLeft + this.layoutWidth - PADDING - SCROLLBAR_WIDTH - 2;
		int bottom = this.listTop + this.listHeight;

		guiGraphics.enableScissor(left, this.listTop, right + SCROLLBAR_WIDTH + 2, bottom);

		if (this.rows.isEmpty()) {
			Component empty = this.service.isReady()
				? Component.translatable("jei_filter.list.empty")
				: Component.translatable("jei_filter.status.not_ready");
			guiGraphics.drawString(this.font, empty, left + 4, this.listTop + 4, COLOR_TEXT_DIM, false);
		}

		int hovered = rowAt(mouseY);
		int first = this.scrollRow;
		int last = Math.min(this.rows.size(), first + this.listHeight / ROW_HEIGHT + 1);

		for (int i = first; i < last; i++) {
			Row row = this.rows.get(i);
			int y = rowTop(i);

			if (i == hovered) {
				guiGraphics.fill(left, y, right, y + ROW_HEIGHT, COLOR_ROW_HOVER);
			} else if (i == this.cursor) {
				guiGraphics.fill(left, y, right, y + ROW_HEIGHT, COLOR_ROW_CURSOR);
			}

			if (row instanceof Row.Category category) {
				drawTickBox(guiGraphics, left + 2, y + 2, category.tick());
				guiGraphics.drawString(this.font, categoryName(category.category()),
					left + 16, y + 3, COLOR_HEADER, false);
				String detail = category.mods() + " mods, " + category.ingredients();
				guiGraphics.drawString(this.font, detail, right - 4 - this.font.width(detail), y + 3,
					COLOR_TEXT_FAINT, false);
				// A divider under the last category separates the two levels.
				if (i + 1 >= this.rows.size() || !(this.rows.get(i + 1) instanceof Row.Category)) {
					guiGraphics.fill(left, y + ROW_HEIGHT - 1, right, y + ROW_HEIGHT, COLOR_DIVIDER);
				}
			} else if (row instanceof Row.Mod mod) {
				String arrow = mod.open() ? "v" : ">";
				guiGraphics.drawString(this.font, arrow, left + 1, y + 3, COLOR_TEXT_DIM, false);
				drawTickBox(guiGraphics, left + 10, y + 2, mod.tick());

				int countWidth = this.font.width(Integer.toString(mod.entry().itemCount()));
				int nameMax = right - 22 - countWidth - (left + 24);
				String name = this.font.plainSubstrByWidth(mod.entry().modName(), Math.max(0, nameMax));
				guiGraphics.drawString(this.font, name, left + 24, y + 3, COLOR_TEXT, false);

				String count = Integer.toString(mod.entry().itemCount());
				guiGraphics.drawString(this.font, count, right - 4 - countWidth, y + 3, COLOR_TEXT_DIM, false);
			} else if (row instanceof Row.Sub sub) {
				drawTickBox(guiGraphics, left + 24, y + 2, sub.tick());
				guiGraphics.drawString(this.font, categoryName(sub.category()),
					left + 38, y + 3, COLOR_TEXT_DIM, false);
				String count = Integer.toString(sub.count());
				guiGraphics.drawString(this.font, count, right - 4 - this.font.width(count), y + 3,
					COLOR_TEXT_FAINT, false);
			}
		}
		guiGraphics.disableScissor();

		renderScrollbar(guiGraphics, right + 2);
	}

	/**
	 * Draws a checkbox in one of its three states: empty for "none of this is shown", a filled square
	 * for "some of it is shown", and a tick for "all of it is shown".
	 */
	private static void drawTickBox(GuiGraphics guiGraphics, int x, int y, FilterOptions.TickState state) {
		guiGraphics.fill(x, y, x + 9, y + 9, 0xFF101010);
		guiGraphics.renderOutline(x, y, 9, 9, COLOR_BORDER);
		switch (state) {
			case ALL -> {
				// A tick, as two strokes.
				guiGraphics.fill(x + 2, y + 4, x + 4, y + 7, COLOR_TICK);
				guiGraphics.fill(x + 4, y + 2, x + 7, y + 5, COLOR_TICK);
			}
			case SOME -> guiGraphics.fill(x + 2, y + 2, x + 7, y + 7, COLOR_PARTIAL);
			case NONE -> {
				// Left empty.
			}
		}
	}

	private void renderScrollbar(GuiGraphics guiGraphics, int x) {
		int maxRow = maxScrollRow();
		if (maxRow <= 0) {
			return;
		}
		int trackTop = this.listTop;
		int trackHeight = this.listHeight;
		guiGraphics.fill(x, trackTop, x + SCROLLBAR_WIDTH, trackTop + trackHeight, 0x40000000);

		int visibleRows = Math.max(1, trackHeight / ROW_HEIGHT);
		int totalRows = Math.max(1, this.rows.size());
		int thumbHeight = Math.max(10, Math.min(trackHeight, trackHeight * visibleRows / totalRows));
		int thumbTop = trackTop + (int) ((trackHeight - thumbHeight) * ((double) this.scrollRow / maxRow));
		guiGraphics.fill(x, thumbTop, x + SCROLLBAR_WIDTH, thumbTop + thumbHeight, COLOR_BORDER);
	}

	// ------------------------------------------------------------------
	// input
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int left = this.layoutLeft + PADDING;
		int right = this.layoutLeft + this.layoutWidth - PADDING;
		if (mouseX < left || mouseX >= right) {
			return false;
		}

		int index = rowAt(mouseY);
		if (index < 0) {
			return false;
		}
		this.cursor = index;

		// Clicking a mod row's disclosure arrow opens its sub-rows instead of hiding the mod.
		Row row = this.rows.get(index);
		if (row instanceof Row.Mod mod && mouseX < left + 10) {
			toggleExpanded(mod);
			return true;
		}
		toggleRow(index);
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (super.mouseScrolled(mouseX, mouseY, delta)) {
			return true;
		}
		if (maxScrollRow() <= 0) {
			return false;
		}
		this.scrollRow = Mth.clamp(this.scrollRow - (int) Math.signum(delta) * 2, 0, maxScrollRow());
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.searchBox != null && this.searchBox.isFocused()
			&& keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_DOWN && keyCode != GLFW.GLFW_KEY_UP) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		switch (keyCode) {
			case GLFW.GLFW_KEY_DOWN -> {
				moveCursor(1);
				return true;
			}
			case GLFW.GLFW_KEY_UP -> {
				moveCursor(-1);
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_DOWN -> {
				moveCursor(Math.max(1, this.listHeight / ROW_HEIGHT));
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_UP -> {
				moveCursor(-Math.max(1, this.listHeight / ROW_HEIGHT));
				return true;
			}
			case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (this.cursor >= 0) {
					toggleRow(this.cursor);
					return true;
				}
				return false;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				setCursorExpanded(true);
				return true;
			}
			case GLFW.GLFW_KEY_LEFT -> {
				setCursorExpanded(false);
				return true;
			}
			default -> {
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
		}
	}

	private void setCursorExpanded(boolean open) {
		if (this.cursor < 0 || this.cursor >= this.rows.size()) {
			return;
		}
		if (this.rows.get(this.cursor) instanceof Row.Mod mod) {
			String modId = mod.entry().modId();
			if (open) {
				this.expanded.add(modId);
			} else {
				this.expanded.remove(modId);
			}
			rebuildRows();
		}
	}

	private void moveCursor(int delta) {
		if (this.rows.isEmpty()) {
			return;
		}
		int next = this.cursor < 0 ? 0 : this.cursor + delta;
		this.cursor = Mth.clamp(next, 0, this.rows.size() - 1);
		ensureVisible(this.cursor);
	}

	@Override
	public void onClose() {
		// What the player ticked is kept; the filter persists to disk as it changes.
		Minecraft.getInstance().setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
