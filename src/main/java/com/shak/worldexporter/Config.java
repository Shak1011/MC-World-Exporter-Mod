package com.shak.worldexporter;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> GITHUB_TOKEN = BUILDER
            .comment("Your GitHub personal access token")
            .define("githubToken", "");

    public static final ModConfigSpec.ConfigValue<String> REPO_OWNER = BUILDER
            .comment("Your GitHub username")
            .define("repoOwner", "");

    public static final ModConfigSpec.ConfigValue<String> REPO_NAME = BUILDER
            .comment("Your GitHub repository name")
            .define("repoName", "MC-World-Viewer");

    public static final ModConfigSpec.IntValue CENTER_X = BUILDER
            .comment("X coordinate of the centre of your base")
            .defineInRange("centerX", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue CENTER_Z = BUILDER
            .comment("Z coordinate of the centre of your base")
            .defineInRange("centerZ", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue RADIUS_CHUNKS = BUILDER
            .comment("How many chunks to export in each direction (6 = 12 chunk diameter)")
            .defineInRange("radiusChunks", 6, 1, 32);

    static final ModConfigSpec SPEC = BUILDER.build();
}