package com.shak.worldexporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class WorldExporterService {

    private final MinecraftServer server;
    private final HttpClient httpClient;

    public WorldExporterService(MinecraftServer server) {
        this.server = server;
        // Java's built-in HTTP client — no extra libraries needed
        this.httpClient = HttpClient.newHttpClient();
    }

    // Called when player clicks "Yes" on the export screen
    // By this point chunks are already read and cached in ServerEvents
    // We just need to do the GitHub upload
    public void export() {
        WorldExporter.LOGGER.info("World Exporter: starting upload...");

        try {
            String token = Config.GITHUB_TOKEN.get();
            String owner = Config.REPO_OWNER.get();
            String repo  = Config.REPO_NAME.get();

            if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
                WorldExporter.LOGGER.warn("World Exporter: GitHub config not set — skipping");
                return;
            }

            // Use the pre-built JSON that was read on the server thread during shutdown
            // We don't touch the world at all here
            String worldName = ServerEvents.cachedWorldName;
            String worldJson = ServerEvents.cachedWorldJson;

            if (worldJson == null || worldName == null) {
                WorldExporter.LOGGER.error("World Exporter: no cached world data available");
                return;
            }

            // Replace spaces with underscores for the GitHub folder name
            // "New World" becomes "New_World" which is cleaner than URL encoding
            // The original worldName is kept for display in index.json
            String safeName = worldName.replace(" ", "_");

            WorldExporter.LOGGER.info("World Exporter: uploading world '{}'", worldName);

            // Push world_data.json to GitHub under the world's folder
            // e.g. worlds/New_World/world_data.json
            String worldDataPath = "worlds/" + safeName + "/world_data.json";
            pushFileToGitHub(token, owner, repo, worldDataPath, worldJson,
                    "Update " + worldName + " world data");

            // Update index.json so the HTML page knows this world exists
            // We pass worldName (with spaces) for display and safeName for the path
            updateIndexJson(token, owner, repo, worldName, safeName);

            WorldExporter.LOGGER.info("World Exporter: upload complete!");

        } catch (Exception e) {
            WorldExporter.LOGGER.error("World Exporter: upload failed", e);
        }
    }

    // Reads all blocks in the configured area and returns them as a JSON string
    // Called on the server thread during shutdown so the world is still accessible
    // Public so ServerEvents can call it directly
    public String buildWorldJson(int centerX, int centerZ, int radius) {

        // Get the overworld — we only export the main dimension for now
        Level world = server.getLevel(Level.OVERWORLD);
        if (world == null) {
            WorldExporter.LOGGER.warn("World Exporter: overworld not found");
            return "{}";
        }

        WorldExporter.LOGGER.info("World Exporter: reading {} chunks...",
                (radius * 2) * (radius * 2));

        // Palette: maps block name → index to avoid repeating strings for every block
        // e.g. { "minecraft:stone" → 0, "minecraft:dirt" → 1 }
        Map<String, Integer> paletteIndex = new HashMap<>();
        JsonArray palette = new JsonArray();
        JsonArray blocks  = new JsonArray();

        // Convert centre block coords to chunk coords
        // >> 4 is the same as Math.floor(x / 16) but faster
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;

        int totalChunks = (radius * 2) * (radius * 2);
        int chunkCount = 0;

        for (int dx = -radius; dx < radius; dx++) {
            for (int dz = -radius; dz < radius; dz++) {
                chunkCount++;
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                WorldExporter.LOGGER.info("World Exporter: chunk ({},{}) [{}/{}]",
                        chunkX, chunkZ, chunkCount, totalChunks);

                // Load the chunk — if it exists on disk it loads instantly
                // If it hasn't been visited it will be generated from scratch
                // This is safe because we're on the server thread during shutdown
                LevelChunk chunk = world.getChunk(chunkX, chunkZ);
                ChunkPos chunkPos = chunk.getPos();

                // World coordinates of this chunk's origin block
                int baseX = chunkPos.getMinBlockX();
                int baseZ = chunkPos.getMinBlockZ();

                // Loop every block in the chunk across the full height range
                // getMinY() = -64, getMaxY() = 320 in modern Minecraft
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world.getMinY(); y < world.getMaxY(); y++) {
                            BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                            BlockState state = chunk.getBlockState(pos);

                            // Skip all air variants — no point sending them to the viewer
                            if (state.isAir()) continue;

                            // Look up the block's registry name e.g. "minecraft:stone"
                            String name = BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock())
                                    .toString();

                            // Add to palette if this block type is new
                            if (!paletteIndex.containsKey(name)) {
                                paletteIndex.put(name, palette.size());
                                palette.add(name);
                            }

                            // Each block entry: [worldX, worldY, worldZ, paletteIndex]
                            JsonArray block = new JsonArray();
                            block.add(baseX + x);
                            block.add(y);
                            block.add(baseZ + z);
                            block.add(paletteIndex.get(name));
                            blocks.add(block);
                        }
                    }
                }
            }
        }

        WorldExporter.LOGGER.info("World Exporter: {} blocks across {} types",
                blocks.size(), palette.size());

        // Build the final JSON object that the HTML viewer expects
        JsonObject root = new JsonObject();
        root.add("palette", palette);
        root.add("blocks", blocks);
        return root.toString();
    }

    // Updates index.json which lists all worlds and their last update time
    // worldName = display name with spaces e.g. "New World"
    // safeName = folder name with underscores e.g. "New_World"
    private void updateIndexJson(String token, String owner,
                                 String repo, String worldName, String safeName)
            throws IOException, InterruptedException {

        // Try to fetch the existing index.json from GitHub
        String existingJson = fetchFileContent(token, owner, repo, "index.json");

        JsonObject index;
        if (existingJson == null) {
            // First time — create a fresh index
            index = new JsonObject();
            index.add("worlds", new JsonArray());
        } else {
            index = com.google.gson.JsonParser.parseString(existingJson).getAsJsonObject();
        }

        JsonArray worlds = index.getAsJsonArray("worlds");

        // Check if this world already exists in the index
        // If so update its timestamp, if not add a new entry
        boolean found = false;
        for (int i = 0; i < worlds.size(); i++) {
            JsonObject w = worlds.get(i).getAsJsonObject();
            if (w.get("name").getAsString().equals(worldName)) {
                w.addProperty("lastUpdated", Instant.now().toString());
                found = true;
                break;
            }
        }

        if (!found) {
            // New world — add it to the index
            // Store both the display name and the safe folder name
            JsonObject newWorld = new JsonObject();
            newWorld.addProperty("name", worldName);       // "New World" — shown in the HTML viewer
            newWorld.addProperty("folder", safeName);      // "New_World" — used in the file path
            newWorld.addProperty("lastUpdated", Instant.now().toString());
            worlds.add(newWorld);
        }

        pushFileToGitHub(token, owner, repo, "index.json",
                index.toString(), "Update world index");
    }

    // Pushes a file to GitHub via the contents API
    // Creates the file if it doesn't exist, updates it if it does
    private void pushFileToGitHub(String token, String owner, String repo,
                                  String path, String content, String commitMessage)
            throws IOException, InterruptedException {

        // GitHub requires file content to be Base64 encoded
        String encoded = Base64.getEncoder().encodeToString(content.getBytes());

        // Get the existing file's SHA — needed to update an existing file
        // Returns null if the file doesn't exist yet (first push)
        String sha = getFileSha(token, owner, repo, path);

        JsonObject body = new JsonObject();
        body.addProperty("message", commitMessage);
        body.addProperty("content", encoded);
        if (sha != null) {
            // Must include SHA when updating an existing file
            body.addProperty("sha", sha);
        }

        String url = "https://api.github.com/repos/" + owner + "/" + repo
                + "/contents/" + path;

        // PUT request to GitHub's contents API
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/vnd.github+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            WorldExporter.LOGGER.info("World Exporter: pushed {} successfully", path);
        } else {
            WorldExporter.LOGGER.error("World Exporter: GitHub returned {} for {} — {}",
                    response.statusCode(), path, response.body());
        }
    }

    // Gets the SHA of a file on GitHub — needed to update existing files
    // Returns null if the file doesn't exist yet
    private String getFileSha(String token, String owner,
                              String repo, String path)
            throws IOException, InterruptedException {

        String url = "https://api.github.com/repos/" + owner + "/" + repo
                + "/contents/" + path;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        // 404 means the file doesn't exist yet — that's fine, we'll create it
        if (response.statusCode() == 404) return null;

        // Extract the SHA from the JSON response using simple string search
        String body = response.body();
        int shaIndex = body.indexOf("\"sha\":\"");
        if (shaIndex == -1) return null;
        int start = shaIndex + 7;
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    // Fetches the decoded text content of a file from GitHub
    // Returns null if the file doesn't exist
    private String fetchFileContent(String token, String owner,
                                    String repo, String path)
            throws IOException, InterruptedException {

        String url = "https://api.github.com/repos/" + owner + "/" + repo
                + "/contents/" + path;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) return null;

        // GitHub returns file content as Base64 with newlines inserted
        // Strip them out before decoding
        String body = response.body();
        int contentIndex = body.indexOf("\"content\":\"");
        if (contentIndex == -1) return null;
        int start = contentIndex + 11;
        int end = body.indexOf("\"", start);
        String encoded = body.substring(start, end)
                .replace("\\n", "")
                .replace("\n", "");
        return new String(Base64.getDecoder().decode(encoded));
    }
}