package com.jeifilter.client;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Opens the mod filter menu from a screen-level mouse event.
 *
 * <p>This is the only click path. JEI's own overlay input-handler classes moved packages and changed
 * contract between JEI 15.20 and 15.55, so hooking them would tie the mod to one JEI version; a
 * screen-level hook does not depend on JEI's internal handler ordering at all. The event fires
 * before vanilla {@code Screen#mouseClicked}, and cancelling it keeps JEI's own overlay from acting
 * on a click that belonged to our button.
 *
 * <p>Both the press AND the release over the button are consumed. JEI's input router remembers the
 * handler that claimed a mouse-down and replays it on the matching release, so letting the release
 * through could resurrect a click that the press already handled.
 */
@OnlyIn(Dist.CLIENT)
public final class JeiFilterInputEvents {
	private static final Logger LOGGER = LogUtils.getLogger();

	private JeiFilterInputEvents() {
	}

	public static void register(net.minecraftforge.eventbus.api.IEventBus eventBus) {
		eventBus.addListener(EventPriority.HIGHEST, false, ScreenEvent.MouseButtonPressed.Pre.class, event -> {
			if (event.getButton() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
				return;
			}
			JeiFilterButton button = JeiFilterButton.getIfCreated();
			if (button == null || !button.isMouseOver(event.getMouseX(), event.getMouseY())) {
				return;
			}
			LOGGER.debug("jei_filter: hopper pressed at ({}, {}); opening the mod filter screen",
				event.getMouseX(), event.getMouseY());
			button.openFilterScreen();
			event.setCanceled(true);
		});

		eventBus.addListener(EventPriority.HIGHEST, false, ScreenEvent.MouseButtonReleased.Pre.class, event -> {
			if (event.getButton() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
				return;
			}
			JeiFilterButton button = JeiFilterButton.getIfCreated();
			if (button == null || !button.isMouseOver(event.getMouseX(), event.getMouseY())) {
				return;
			}
			event.setCanceled(true);
		});
	}
}
