package com.shak.worldexporter;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public class ServerEvents {

    public static MinecraftServer currentServer;
    public static boolean shouldShowExportPrompt = false;

    // We store the pre-built JSON and world name here
    // so the export thread can access them without touching the world
    public static String cachedWorldJson = null;
    public static String cachedWorldName = null;

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        currentServer = event.getServer();

        // Reset the cache when a new world starts
        // so we don't accidentally upload data from a previous session
        cachedWorldJson = null;
        cachedWorldName = null;

        WorldExporter.LOGGER.info("World Exporter: server started");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        currentServer = event.getServer();

        // The server is shutting down but the world is still readable
        // This is the ONLY safe place to read chunk data
        try {
            WorldExporter.LOGGER.info("World Exporter: server stopping, reading chunks now...");

            // Get the world name — used as the folder name in GitHub
            cachedWorldName = currentServer.getWorldData().getLevelName();

            // Read the config values for where to export
            int centerX = Config.CENTER_X.getAsInt();
            int centerZ = Config.CENTER_Z.getAsInt();
            int radius  = Config.RADIUS_CHUNKS.getAsInt();

            // Create the service and read the chunks
            // This stores the JSON in cachedWorldJson for the upload thread to use
            WorldExporterService service = new WorldExporterService(currentServer);
            cachedWorldJson = service.buildWorldJson(centerX, centerZ, radius);

            WorldExporter.LOGGER.info("World Exporter: chunks read successfully, ready to upload");

        } catch (Exception e) {
            WorldExporter.LOGGER.error("World Exporter: failed to read chunks", e);
            // If reading fails, clear the cache so we don't upload bad data
            cachedWorldJson = null;
            cachedWorldName = null;
        }

        // Set the flag so ClientEvents knows to show the prompt
        shouldShowExportPrompt = true;
    }
}