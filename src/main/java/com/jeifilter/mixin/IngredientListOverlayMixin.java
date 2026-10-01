package com.jeifilter.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.jeifilter.client.JeiFilterButton;

import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.overlay.IngredientListOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Adds the hopper filter button to the left edge of JEI's ingredient list search bar.
 *
 * <p>JEI builds the bottom strip inside {@code IngredientListOverlay#updateBounds} from a local
 * named {@code searchAndConfigArea}, then splits it into "search field on the left, config button
 * on the right". This mixin shrinks that local by {@link JeiFilterButton#SIZE} pixels on the left
 * and lets JEI's own code place everything else, so the search field and config button keep JEI's
 * exact layout and only the search field is narrower. The leftover 20px on the left is ours.
 *
 * <p>It then also:
 * <ul>
 *   <li>places the hopper button from the search field's own (already shrunk) bounds,</li>
 *   <li>draws it right after JEI draws the search field, and</li>
 *   <li>puts its click handler first in JEI's input chain, because the first handler that accepts
 *       a click wins and the search field would otherwise swallow the click and take focus.</li>
 * </ul>
 *
 * <p>Every injection uses {@code require = 1} (from {@code defaultRequire} in
 * {@code jei_filter.mixins.json}), so if a future JEI renames or reshapes these members the mod
 * fails loudly at startup instead of quietly losing its button. The target is not remapped
 * ({@code remap = false}) because JEI is a mod, not Minecraft.
 */
@Mixin(value = IngredientListOverlay.class, remap = false)
public abstract class IngredientListOverlayMixin {
	@Shadow
	public abstract boolean isListDisplayed();

	/**
	 * The slot taken off the left of JEI's search/config strip, remembered between the two
	 * injections that make up one layout pass. Lives in the target class, so it is per overlay.
	 */
	@Unique
	@Nullable
	private static ImmutableRect2i jei_filter$buttonSlot;

	/**
	 * Reserve {@link JeiFilterButton#SIZE} pixels at the left of JEI's search/config strip.
	 *
	 * <p>{@code searchAndConfigArea} is a named local in JEI's {@code LocalVariableTable}, which is
	 * what makes this injection (rather than a fragile slot ordinal) possible. Verified against
	 * {@code jei-1.20.1-forge-15.62.0.217.jar} with {@code javap -l}.
	 */
	@ModifyVariable(method = "updateBounds", at = @At("STORE"), name = "searchAndConfigArea")
	private ImmutableRect2i jei_filter$reserveButtonSpace(ImmutableRect2i searchAndConfigArea) {
		// Remember where the button would go even before the button exists, so the very first
		// layout pass already leaves the gap empty instead of drawing the search field over it.
		jei_filter$buttonSlot = null;
		if (searchAndConfigArea.getWidth() <= JeiFilterButton.SIZE) {
			// No room to give: leave JEI's own "not enough space" handling untouched.
			return searchAndConfigArea;
		}
		jei_filter$buttonSlot = searchAndConfigArea.keepLeft(JeiFilterButton.SIZE);
		return searchAndConfigArea.cropLeft(JeiFilterButton.SIZE);
	}

	/**
	 * Hand the hopper button the slot that {@link #jei_filter$reserveButtonSpace} freed.
	 *
	 * <p>This reads the slot remembered during the layout pass rather than deriving it from the
	 * search field's bounds: JEI has by now applied {@code cropRight(BUTTON_SIZE)} and moved the
	 * field right by 4px, so the field no longer starts where the slot does.
	 */
	@Inject(method = "updateBounds", at = @At("TAIL"))
	private void jei_filter$placeButton(CallbackInfo callbackInfo) {
		JeiFilterButton filterButton = JeiFilterButton.getIfCreated();
		if (filterButton == null) {
			return;
		}
		ImmutableRect2i slot = jei_filter$buttonSlot;
		if (slot == null || !this.isListDisplayed()) {
			filterButton.clearArea();
			return;
		}
		filterButton.setArea(slot);
	}

	/** Draw the hopper on the same layer as the search field, right after JEI renders it. */
	@Inject(method = "drawScreen", at = @At("TAIL"))
	private void jei_filter$drawButton(Minecraft minecraft, GuiGraphics guiGraphics, int mouseX, int mouseY,
									   float partialTicks, CallbackInfo callbackInfo) {
		JeiFilterButton filterButton = JeiFilterButton.getIfCreated();
		if (filterButton == null || !filterButton.hasArea()) {
			return;
		}
		filterButton.draw(guiGraphics, mouseX, mouseY);
	}

	/** Tooltip for the hopper, drawn with JEI's other overlay tooltips. */
	@Inject(method = "drawTooltips", at = @At("TAIL"))
	private void jei_filter$drawButtonTooltip(Minecraft minecraft, GuiGraphics guiGraphics, int mouseX, int mouseY,
											  CallbackInfo callbackInfo) {
		JeiFilterButton filterButton = JeiFilterButton.getIfCreated();
		if (filterButton == null || !filterButton.isMouseOver(mouseX, mouseY)) {
			return;
		}
		filterButton.drawTooltip(guiGraphics, mouseX, mouseY);
	}
}
