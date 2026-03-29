package com.shak.worldexporter;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(WorldExporter.MODID)
public class WorldExporter {

    public static final String MODID = "worldexporter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldExporter(IEventBus modEventBus, ModContainer modContainer) {
        // Register config
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register server side events
        NeoForge.EVENT_BUS.register(ServerEvents.class);

        // Register client side events
        // We wrap this in a dist check so it only runs on the client
        // and doesn't crash on a dedicated server
        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
            NeoForge.EVENT_BUS.register(ClientEvents.class);
        }
    }
}