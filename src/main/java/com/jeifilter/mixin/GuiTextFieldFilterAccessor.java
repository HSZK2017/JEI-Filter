package com.jeifilter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.GuiTextFieldFilter;

/**
 * JEI never exposes the search field's bounds, and we need them to know where to put the hopper
 * button. The field is private in {@code GuiTextFieldFilter}, so it is read through an accessor.
 */
@Mixin(value = GuiTextFieldFilter.class, remap = false)
public interface GuiTextFieldFilterAccessor {
	@Accessor("area")
	ImmutableRect2i jei_filter$getArea();
}
