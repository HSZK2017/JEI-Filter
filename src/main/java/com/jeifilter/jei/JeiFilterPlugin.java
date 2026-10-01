package com.jeifilter.jei;

import com.jeifilter.JeiFilterMod;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The bridge between JEI and the mod filter.
 *
 * <p>JEI's GUI module only exists on the client, so this plugin is client-only as well.
 */
@OnlyIn(Dist.CLIENT)
@JeiPlugin
public final class JeiFilterPlugin implements IModPlugin {
	private static final ResourceLocation PLUGIN_UID = new ResourceLocation(JeiFilterMod.MOD_ID, "plugin");

	@Override
	public ResourceLocation getPluginUid() {
		return PLUGIN_UID;
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		JeiFilterService.get().onRuntimeAvailable(jeiRuntime, JeiFilterMod.configDir());
	}

	@Override
	public void onRuntimeUnavailable() {
		JeiFilterService.get().onRuntimeUnavailable();
	}
}