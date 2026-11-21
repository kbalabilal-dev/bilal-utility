package com.bilalutility.endcity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local-only End City Finder settings. */
public final class EndCityFinderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("endcity_finder.json");

    public static String seed = "";
    public static int scanRadius = 200_000;
    public static int ignoreSpawnRadius = 50_000;
    public static int centerX = 0;
    public static int centerZ = 0;
    public static int clusterSize = 2_000;
    public static int targetX;
    public static int targetZ;
    public static boolean hasTarget;

    private static final class Data {
        String seed = "";
        int scanRadius = 200_000;
        int ignoreSpawnRadius = 50_000;
        int centerX = 0;
        int centerZ = 0;
        int clusterSize = 2_000;
        int targetX;
        int targetZ;
        boolean hasTarget;
    }

    private EndCityFinderConfig() {}

    public static void load() {
        if (!Files.exists(PATH)) return;
        try (Reader reader = Files.newBufferedReader(PATH)) {
            Data d = GSON.fromJson(reader, Data.class);
            if (d == null) return;
            seed = d.seed == null ? "" : d.seed;
            scanRadius = clamp(d.scanRadius, 1_000, 400_000);
            ignoreSpawnRadius = clamp(d.ignoreSpawnRadius, 0, 400_000);
            centerX = clamp(d.centerX, -30_000_000, 30_000_000);
            centerZ = clamp(d.centerZ, -30_000_000, 30_000_000);
            clusterSize = clamp(d.clusterSize, 500, 20_000);
            targetX = d.targetX;
            targetZ = d.targetZ;
            hasTarget = d.hasTarget;
        } catch (Exception ignored) {
            // Keep safe defaults if the local config is damaged.
        }
    }

    public static void save() {
        Data d = new Data();
        d.seed = seed;
        d.scanRadius = scanRadius;
        d.ignoreSpawnRadius = ignoreSpawnRadius;
        d.centerX = centerX;
        d.centerZ = centerZ;
        d.clusterSize = clusterSize;
        d.targetX = targetX;
        d.targetZ = targetZ;
        d.hasTarget = hasTarget;
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(d, writer);
            }
        } catch (Exception ignored) {
            // A config write failure must not interrupt gameplay.
        }
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static void setTarget(int x, int z) {
        targetX = x;
        targetZ = z;
        hasTarget = true;
        save();
    }

    public static void clearTarget() {
        hasTarget = false;
        save();
    }
}
