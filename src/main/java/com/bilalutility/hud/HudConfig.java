package com.bilalutility.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** HUD settings, saved in config/bilalhud.json */
public final class HudConfig {
    public static final class Data {
        public boolean enabled = true;
        public boolean armor = true;          // armor durability next to the hotbar
        public int armorText = 0;             // 0 = percent, 1 = durability left as a number
        public boolean swapSides = false;     // false: armor left of the hotbar, counters right
        public boolean hideZero = true;       // hide counters (except totems) when you have none
        // item counters
        public boolean totems = true;
        public boolean crystals = true;
        public boolean pearls = true;
        public boolean gapples = true;
        public boolean glowstone = true;
        public boolean anchors = true;
        public boolean obsidian = true;
        public boolean xp = true;
        // info lines
        public boolean combat = true;         // "Combat Xs" while the server shows combat on the hotbar
        public boolean ping = true;
        public boolean fps = false;
        // enemy gear panel (only what the server sends to every client: armor + both hands)
        public boolean targetGear = true;
        public boolean targetCrosshairOnly = false; // false: also the nearest player within targetRange
        public int targetRange = 12;                // blocks (edit in config/bilalhud.json)
        public int targetPos = 0;                   // 0 = right side, 1 = left side, 2 = below the crosshair
    }

    public static Data d = new Data();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HudConfig() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("bilalhud.json");
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded == null) return;
            loaded.armorText = Math.max(0, Math.min(1, loaded.armorText));
            loaded.targetPos = Math.max(0, Math.min(2, loaded.targetPos));
            loaded.targetRange = loaded.targetRange <= 0 ? 12 : Math.min(64, loaded.targetRange);
            d = loaded;
        } catch (Exception ignored) {
            // keep defaults if the file is broken
        }
    }

    public static void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(d, w);
            }
        } catch (IOException ignored) {
            // not fatal
        }
    }
}
