package com.jeifilter.client;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.jeifilter.client.gui.ModFilterScreen;
import com.jeifilter.jei.JeiFilterService;
import com.mojang.logging.LogUtils;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.GuiIconButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The hopper button that sits at the left edge of JEI's search bar.
 *
 * <p>The button itself is a JEI {@link GuiIconButton}, so it reuses JEI's own button frame,
 * hover highlight, click sound and input handler. Only the placement (see
 * {@code IngredientListOverlayMixin}), the icon tint and the tooltip are ours.
 *
 * <p>The area is owned by the mixin rather than by JEI's widget hierarchy, so this class is the
 * single source of truth for "is the button on screen right now".
 */
@OnlyIn(Dist.CLIENT)
public final class JeiFilterButton {
	/** Must match JEI's own {@code IngredientListOverlay.BUTTON_SIZE}. */
	public static final int SIZE = 20;

	/** The "a filter is active" badge colour. */
	private static final int BADGE_COLOR = 0xFF55FF55;

	private static final Logger LOGGER = LogUtils.getLogger();

	@Nullable
	private static JeiFilterButton instance;

	private final IDrawable icon;
	private final GuiIconButton button;
	private ImmutableRect2i area = ImmutableRect2i.EMPTY;

	private JeiFilterButton(IGuiHelper guiHelper) {
		this.icon = guiHelper.createDrawableItemLike(Items.HOPPER);
		this.button = new GuiIconButton(this.icon, b -> openFilterScreen());
		LOGGER.info("jei_filter: hopper filter button created for the JEI search bar");
	}

	/**
	 * Creates the button the first time a JEI runtime is available and returns the singleton.
	 * Safe to call repeatedly.
	 */
	public static JeiFilterButton getOrCreate(IGuiHelper guiHelper) {
		JeiFilterButton current = instance;
		if (current == null) {
			current = new JeiFilterButton(guiHelper);
			instance = current;
		}
		return current;
	}

	/** The button, or {@code null} before JEI has handed us a runtime. */
	@Nullable
	public static JeiFilterButton getIfCreated() {
		return instance;
	}

	// ------------------------------------------------------------------
	// placement, driven by the mixin
	// ------------------------------------------------------------------

	public void setArea(ImmutableRect2i area) {
		this.area = area;
		this.button.updateBounds(area);
		this.button.visible = !area.isEmpty();
	}

	public void clearArea() {
		setArea(ImmutableRect2i.EMPTY);
	}

	public ImmutableRect2i area() {
		return this.area;
	}

	public boolean hasArea() {
		return !this.area.isEmpty();
	}

	// ------------------------------------------------------------------
	// rendering
	// ------------------------------------------------------------------

	/**
	 * Clears the button's visual pressed state.
	 *
	 * <p>A fresh handler is asked to unfocus rather than a cached one. JEI changed
	 * {@code GuiIconButton}'s input-handler type between 15.20 and 15.55, so holding a reference to
	 * it is not portable; JEI's own {@code unfocus()} clears the button it wraps either way.
	 */
	public void clearPressed() {
		this.button.createInputHandler().unfocus();
	}

	/**
	 * Draws the button. While a filter is active a green dot is painted into the bottom right of the
	 * button, so the player can tell at a glance that JEI is hiding something.
	 *
	 * <p>The dot is drawn rather than a shader tint on the icon: JEI renders the icon through the
	 * item render path, which does not honour {@code RenderSystem.setShaderColor}, so a tint is
	 * invisible in practice (measured in-game).
	 */
	public void draw(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (!hasArea()) {
			return;
		}
		this.button.render(guiGraphics, mouseX, mouseY, 0.0F);

		if (JeiFilterService.get().isFilterActive()) {
			int size = 4;
			int x = this.area.getX() + this.area.getWidth() - size - 2;
			int y = this.area.getY() + this.area.getHeight() - size - 2;
			guiGraphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF101010);
			guiGraphics.fill(x, y, x + size, y + size, BADGE_COLOR);
		}
	}

	public boolean isMouseOver(double mouseX, double mouseY) {
		return hasArea() && this.area.contains(mouseX, mouseY);
	}

	public void drawTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		JeiFilterService service = JeiFilterService.get();
		int hiddenIngredients = service.hiddenIngredientCount();
		Component tooltip;
		if (hiddenIngredients > 0) {
			tooltip = Component.translatable("jei_filter.tooltip.button.active", hiddenIngredients);
		} else if (!service.isReady()) {
			tooltip = Component.translatable("jei_filter.status.not_ready");
		} else {
			tooltip = Component.translatable("jei_filter.tooltip.button");
		}
		guiGraphics.renderTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
	}

	/**
	 * Opens the mod filter menu on top of whatever screen JEI is currently overlaid on.
	 *
	 * <p>Called from the button's own input handler once a full press-and-release on the hopper has
	 * been observed, rather than from JEI's {@code OnPress}, so it does not depend on the two-pass
	 * simulate/execute click protocol.
	 */
	public void openFilterScreen() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof ModFilterScreen) {
			return;
		}
		minecraft.setScreen(new ModFilterScreen(minecraft.screen));
	}
}
