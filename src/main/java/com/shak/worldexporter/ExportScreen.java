package com.shak.worldexporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class ExportScreen extends Screen {

    // The screen to go back to after we're done (the title screen)
    private final Screen lastScreen;

    public ExportScreen(Screen lastScreen) {
        super(Component.literal("Upload World?"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        // "Yes" button — triggers upload then quits to title
        this.addRenderableWidget(Button.builder(
                Component.literal("Yes, upload to GitHub"),
                button -> onYesClicked()
        ).bounds(this.width / 2 - 155, this.height / 2, 150, 20).build());

        // "No" button — just quits to title without uploading
        this.addRenderableWidget(Button.builder(
                Component.literal("No, just quit"),
                button -> onNoClicked()
        ).bounds(this.width / 2 + 5, this.height / 2, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // Call super first — this prepares all registered widgets (buttons etc)
        super.extractRenderState(graphics, mouseX, mouseY, a);

        // Draw the main question text
        // Colors need alpha channel in 26.1 — 0xFF prefix = fully opaque
        graphics.centeredText(
                this.font,
                Component.literal("Upload this world to your viewer?"),
                this.width / 2,
                this.height / 2 - 30,
                0xFFFFFFFF // white, fully opaque
        );

        // Draw the subtitle in grey
        graphics.centeredText(
                this.font,
                Component.literal("This may take a moment"),
                this.width / 2,
                this.height / 2 - 15,
                0xFFAAAAAA // grey, fully opaque
        );
    }

    private void onYesClicked() {
        WorldExporter.LOGGER.info("User chose to upload — starting");

        // Disable both buttons immediately to prevent clicking again
        // while the upload is in progress
        this.children().forEach(child -> {
            if (child instanceof Button btn) {
                btn.active = false;
            }
        });

        // Run the GitHub upload on a background thread so the UI stays responsive
        // The chunk JSON is already built — this is just the network upload
        Thread exportThread = new Thread(() -> {
            try {
                WorldExporterService service = new WorldExporterService(ServerEvents.currentServer);
                service.export();
            } catch (Exception e) {
                WorldExporter.LOGGER.error("Upload failed", e);
            } finally {
                // Always quit to title when done, even if upload failed
                // execute() ensures this runs on the main thread
                // since we're currently on the background upload thread
                Minecraft.getInstance().execute(() ->
                        Minecraft.getInstance().setScreen(new TitleScreen()));
            }
        }, "WorldExporter-Thread");

        exportThread.setDaemon(true);
        exportThread.start();
    }

    private void onNoClicked() {
        // Just go back to title screen without uploading
        Minecraft.getInstance().setScreen(new TitleScreen());
    }

    // Prevent the player from closing this screen with Escape
    // They must make a choice
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}