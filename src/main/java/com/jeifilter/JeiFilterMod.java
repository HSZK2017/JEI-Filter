package com.jeifilter;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * JEI Filter: adds a hopper button to JEI's search bar that opens a per-mod visibility filter.
 *
 * <p>Client only. The mod metadata declares JEI as a {@code CLIENT} dependency, so Forge refuses to
 * load this mod at all on a dedicated server and none of the client classes here are reached there.
 */
@Mod(JeiFilterMod.MOD_ID)
public final class JeiFilterMod {
	public static final String MOD_ID = "jei_filter";
	public static final String MOD_NAME = "JEI Filter";

	private static final Logger LOGGER = LogUtils.getLogger();

	public JeiFilterMod() {
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		IEventBus forgeEventBus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;
		modEventBus.addListener(this::onCommonSetup);

		// The screen-level click hook for the hopper button. Registered through DistExecutor so the
		// client-only event classes are never resolved on a dedicated server.
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
			() -> () -> com.jeifilter.client.JeiFilterInputEvents.register(forgeEventBus));

		LOGGER.info("{} is loading (client side = {})", MOD_NAME, Dist.CLIENT.isClient());
	}

	/** The directory JEI Filter writes its settings to. */
	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
		// The JEI plugin is discovered through the @JeiPlugin annotation, nothing to do here.
		LOGGER.debug("{} common setup complete", MOD_NAME);
	}
}
