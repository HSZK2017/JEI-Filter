package com.jeifilter.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.jeifilter.filter.FilterMode;
import com.jeifilter.filter.FilterOptions;
import com.jeifilter.jei.JeiFilterService;
import com.jeifilter.filter.ModEntry;

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
 * <p>Every checkbox takes effect immediately, so the player can watch JEI react while the
 * screen is open, and the whole selection is written to disk as it changes.
 */
@OnlyIn(Dist.CLIENT)
public final class ModFilterScreen extends Screen {
	private static final int MIN_WIDTH = 260;
	private static final int MAX_WIDTH = 460;
	private static final int MIN_HEIGHT = 160;
	private static final int MAX_HEIGHT = 290;
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
	private static final int COLOR_DIVIDER = 0x60FFFFFF;
	private static final int COLOR_CHECK = 0xFF55FF55;

	private final Screen parent;
	private final JeiFilterService service = JeiFilterService.get();

	private EditBox searchBox;
	private Button modeButton;
	private Button sortButton;
	private Button allButton;
	private Button noneButton;
	private Button invertButton;

	private String query = "";
	/** Entries matching {@link #query}, in display order. */
	private List<ModEntry> filtered = List.of();
	private ModEntry.Sort sort = ModEntry.Sort.NAME;
	private int cursor = -1;
	private int scrollRow;

	private int layoutLeft;
	private int layoutTop;
	private int layoutWidth;
	private int layoutHeight;
	private int headerHeight;
	private int modeHintY;
	private int listTop;
	private int listHeight;

	public ModFilterScreen(Screen parent) {
		super(Component.translatable("jei_filter.screen.title"));
		this.parent = parent;
	}

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
		// Header: the title line, then one line explaining what the current mode does.
		this.headerHeight = 12 + 11;
		int y = this.layoutTop + PADDING + this.headerHeight;
		this.modeHintY = this.layoutTop + PADDING + 12;

		// Row 1: mode toggle (what "checked" means) and sort order.
		this.modeButton = Button.builder(modeLabel(), b -> {
			FilterMode next = this.service.options().mode().next();
			this.service.setMode(next);
			refreshWidgets();
		}).pos(contentLeft, y).size(contentWidth / 2 - 2, WIDGET_HEIGHT).build();
		addRenderableWidget(this.modeButton);

		this.sortButton = Button.builder(sortLabel(), b -> {
			this.sort = this.sort.next();
			recomputeFiltered();
			refreshWidgets();
		}).pos(contentLeft + contentWidth / 2 + 2, y).size(contentWidth / 2 - 2, WIDGET_HEIGHT).build();
		addRenderableWidget(this.sortButton);

		y += WIDGET_HEIGHT + 4;

		// Row 2: search box plus the three selection shortcuts.
		// The trailing -2 keeps the search box clear of the "All" button: three buttons plus three
		// 2px gaps are subtracted, and the box would otherwise end exactly on the button.
		int helperWidth = 44;
		int searchWidth = contentWidth - (helperWidth + 2) * 3 - 2;
		this.searchBox = new EditBox(this.font, contentLeft, y, searchWidth, WIDGET_HEIGHT,
			Component.translatable("jei_filter.search.hint"));
		this.searchBox.setHint(Component.translatable("jei_filter.search.hint").withStyle(ChatFormatting.DARK_GRAY));
		this.searchBox.setResponder(text -> {
			this.query = text.trim().toLowerCase(Locale.ROOT);
			recomputeFiltered();
			this.scrollRow = 0;
			this.cursor = -1;
		});
		this.searchBox.setValue(this.query);
		addRenderableWidget(this.searchBox);

		int bx = contentLeft + searchWidth + 2;
		this.allButton = Button.builder(Component.translatable("jei_filter.button.all"), b -> selectVisible(true))
			.pos(bx, y).size(helperWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.allButton);
		bx += helperWidth + 2;
		this.noneButton = Button.builder(Component.translatable("jei_filter.button.none"), b -> selectVisible(false))
			.pos(bx, y).size(helperWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.noneButton);
		bx += helperWidth + 2;
		this.invertButton = Button.builder(Component.translatable("jei_filter.button.invert"), b -> invertVisible())
			.pos(bx, y).size(helperWidth, WIDGET_HEIGHT).build();
		addRenderableWidget(this.invertButton);

		y += WIDGET_HEIGHT + 4;
		this.listTop = y;
		// Leave room for the footer line plus the Done button at the bottom of the frame.
		this.listHeight = Math.max(ROW_HEIGHT, this.layoutTop + height - PADDING - 12 - WIDGET_HEIGHT - 6 - y);

		int footerY = this.layoutTop + height - PADDING - WIDGET_HEIGHT;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
			.pos(this.layoutLeft + width - PADDING - 100, footerY)
			.size(100, WIDGET_HEIGHT)
			.build());

		recomputeFiltered();
		refreshWidgets();
	}

	private Component modeLabel() {
		return Component.translatable("jei_filter.button.mode",
			Component.translatable("jei_filter.mode." + this.service.options().mode().getId()));
	}

	private Component sortLabel() {
		return Component.translatable("jei_filter.button.sort",
			Component.translatable("jei_filter.sort." + this.sort.name().toLowerCase(Locale.ROOT)));
	}

	private void recomputeFiltered() {
		List<ModEntry> result = new ArrayList<>();
		for (ModEntry entry : this.service.modEntries()) {
			if (entry.matches(this.query)) {
				result.add(entry);
			}
		}
		result.sort(this.sort.comparator());
		this.filtered = List.copyOf(result);
		this.cursor = Mth.clamp(this.cursor, -1, this.filtered.size() - 1);
		clampScroll();
	}

	private void refreshWidgets() {
		boolean empty = this.filtered.isEmpty();
		if (this.allButton != null) {
			this.allButton.active = !empty;
		}
		if (this.noneButton != null) {
			this.noneButton.active = !empty;
		}
		if (this.invertButton != null) {
			this.invertButton.active = !empty;
		}
		if (this.modeButton != null) {
			this.modeButton.setMessage(modeLabel());
		}
		if (this.sortButton != null) {
			this.sortButton.setMessage(sortLabel());
		}
	}

	// ------------------------------------------------------------------
	// selection helpers
	// ------------------------------------------------------------------

	private List<String> visibleModIds() {
		List<String> ids = new ArrayList<>(this.filtered.size());
		for (ModEntry entry : this.filtered) {
			ids.add(entry.modId());
		}
		return ids;
	}

	private void selectVisible(boolean selected) {
		List<String> ids = visibleModIds();
		if (!ids.isEmpty()) {
			this.service.setSelected(ids, selected);
		}
	}

	private void invertVisible() {
		List<String> ids = visibleModIds();
		if (!ids.isEmpty()) {
			this.service.invertSelection(ids);
		}
	}

	private void toggleRow(int index) {
		if (index < 0 || index >= this.filtered.size()) {
			return;
		}
		this.service.toggleMod(this.filtered.get(index).modId());
	}

	// ------------------------------------------------------------------
	// scrolling
	// ------------------------------------------------------------------

	private int maxScrollRow() {
		int visibleRows = Math.max(1, this.listHeight / ROW_HEIGHT);
		return Math.max(0, this.filtered.size() - visibleRows);
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
		if (index < 0 || index >= this.filtered.size()) {
			return -1;
		}
		return index;
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

		FilterOptions options = this.service.options();
		Component status;
		if (!this.service.isReady()) {
			status = Component.translatable("jei_filter.status.not_ready").withStyle(ChatFormatting.RED);
		} else {
			status = Component.translatable("jei_filter.status.summary",
				this.filtered.size(), this.service.modEntries().size(), this.service.hiddenIngredientCount());
		}
		guiGraphics.drawString(this.font, status, left, y, COLOR_TEXT_DIM, false);

		int hiddenMods = this.service.hiddenModCount();
		Component rightText;
		if (hiddenMods > 0) {
			rightText = Component.translatable("jei_filter.status.hidden_mods", hiddenMods)
				.withStyle(ChatFormatting.GOLD);
		} else {
			rightText = Component.translatable("jei_filter.status.nothing_hidden")
				.withStyle(ChatFormatting.DARK_GRAY);
		}
		int available = this.layoutLeft + this.layoutWidth - PADDING - 108;
		if (available - left > this.font.width(rightText)) {
			guiGraphics.drawString(this.font, rightText, available - this.font.width(rightText), y, COLOR_TEXT_DIM, false);
		}

		// A one line explanation of what the current mode does, under the title.
		Component modeHint = Component.translatable(
			"jei_filter.status.hint." + options.mode().getId());
		guiGraphics.drawString(this.font, modeHint,
			this.layoutLeft + PADDING, this.modeHintY, COLOR_TEXT_FAINT, false);
	}

	private void renderList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		int left = this.layoutLeft + PADDING;
		int right = this.layoutLeft + this.layoutWidth - PADDING - SCROLLBAR_WIDTH - 2;
		int bottom = this.listTop + this.listHeight;

		guiGraphics.enableScissor(left, this.listTop, right + SCROLLBAR_WIDTH + 2, bottom);

		if (this.filtered.isEmpty()) {
			Component empty = this.service.isReady()
				? Component.translatable("jei_filter.list.empty")
				: Component.translatable("jei_filter.status.not_ready");
			guiGraphics.drawString(this.font, empty, left + 4, this.listTop + 4, COLOR_TEXT_DIM, false);
		}

		int hovered = rowAt(mouseY);
		int first = this.scrollRow;
		int visibleRows = this.listHeight / ROW_HEIGHT + 1;
		int last = Math.min(this.filtered.size(), first + visibleRows);

		for (int i = first; i < last; i++) {
			ModEntry entry = this.filtered.get(i);
			int y = rowTop(i);
			boolean selected = this.service.options().isSelected(entry.modId());
			boolean hidden = this.service.options().shouldHide(entry.modId());

			if (i == hovered) {
				guiGraphics.fill(left, y, right, y + ROW_HEIGHT, COLOR_ROW_HOVER);
			} else if (i == this.cursor) {
				guiGraphics.fill(left, y, right, y + ROW_HEIGHT, COLOR_ROW_CURSOR);
			}

			drawCheckbox(guiGraphics, left + 2, y + 2, selected);

			int countWidth = this.font.width(Integer.toString(entry.itemCount()));
			int nameMax = right - 22 - countWidth - (left + 16);
			String name = this.font.plainSubstrByWidth(entry.modName(), Math.max(0, nameMax));
			int textColor = hidden ? COLOR_TEXT_DIM : COLOR_TEXT;
			guiGraphics.drawString(this.font, name, left + 16, y + 3, textColor, false);

			// Draw the mod id after the name when there is room for a useful amount of it.
			int nameWidth = this.font.width(name);
			int idSpace = nameMax - nameWidth - 6;
			if (idSpace > 16) {
				String id = this.font.plainSubstrByWidth(entry.modId(), idSpace);
				guiGraphics.drawString(this.font, id, left + 16 + nameWidth + 6, y + 3, COLOR_TEXT_FAINT, false);
			}

			String count = Integer.toString(entry.itemCount());
			guiGraphics.drawString(this.font, count, right - 4 - countWidth, y + 3, COLOR_TEXT_DIM, false);
		}
		guiGraphics.disableScissor();

		renderScrollbar(guiGraphics, right + 2);
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
		int totalRows = Math.max(1, this.filtered.size());
		int thumbHeight = Math.max(10, Math.min(trackHeight, trackHeight * visibleRows / totalRows));
		int thumbTop = trackTop + (int) ((trackHeight - thumbHeight) * ((double) this.scrollRow / maxRow));
		guiGraphics.fill(x, thumbTop, x + SCROLLBAR_WIDTH, thumbTop + thumbHeight, COLOR_BORDER);
	}

	private static void drawCheckbox(GuiGraphics guiGraphics, int x, int y, boolean checked) {
		guiGraphics.fill(x, y, x + 9, y + 9, 0xFF101010);
		guiGraphics.renderOutline(x, y, 9, 9, COLOR_BORDER);
		if (checked) {
			guiGraphics.fill(x + 2, y + 2, x + 7, y + 7, COLOR_CHECK);
		}
	}

	// ------------------------------------------------------------------
	// input
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
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
		if (this.searchBox != null && this.searchBox.isFocused() &&
			keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_DOWN && keyCode != GLFW.GLFW_KEY_UP) {
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
			default -> {
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
		}
	}

	private void moveCursor(int delta) {
		if (this.filtered.isEmpty()) {
			return;
		}
		int next = this.cursor < 0 ? 0 : this.cursor + delta;
		this.cursor = Mth.clamp(next, 0, this.filtered.size() - 1);
		ensureVisible(this.cursor);
	}

	@Override
	public void onClose() {
		// What the player checked is kept; the mod filter persists to disk as it changes.
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** For tests: the mods currently listed after the search box filter is applied. */
	public List<ModEntry> visibleEntries() {
		return this.filtered;
	}

	/** For tests: the row the keyboard cursor is on, or -1. */
	public int cursorRow() {
		return this.cursor;
	}
}
