package com.shak.worldexporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

public class ClientEvents {

    // Fires whenever a new screen is about to open
    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {

        Screen incoming = event.getScreen();
        Minecraft mc = Minecraft.getInstance();

        // We intercept when:
        // 1. The incoming screen is the TitleScreen (player is quitting)
        // 2. The server stopping flag is set (player just quit from a world)
        // 3. We're not already showing our ExportScreen (avoid infinite loop)
        if (incoming instanceof TitleScreen
                && ServerEvents.shouldShowExportPrompt
                && !(mc.screen instanceof ExportScreen)) {

            // Reset the flag immediately so we don't show the prompt again
            ServerEvents.shouldShowExportPrompt = false;

            // Cancel the normal transition to title screen
            event.setCanceled(true);

            // Show our export prompt instead
            mc.setScreen(new ExportScreen(incoming));
        }
    }
}