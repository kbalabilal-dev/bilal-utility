package com.bilalutility.endcity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Incremental local cache. Each entry is keyed by the decimal world seed. */
public final class EndCityCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("endcity_cache.json");

    public static final class Entry {
        public String seed;
        public int centerX;
        public int centerZ;
        public int scanRadius;
        public int ignoreSpawnRadius;
        public boolean complete;
        public List<EndCity> cities = new ArrayList<>();
    }

    private static final class FileData {
        List<Entry> entries = new ArrayList<>();
    }

    private EndCityCache() {}

    public static synchronized Entry load(String seed) {
        if (!Files.exists(PATH)) return null;
        try (Reader reader = Files.newBufferedReader(PATH)) {
            FileData data = GSON.fromJson(reader, FileData.class);
            if (data == null || data.entries == null) return null;
            for (Entry entry : data.entries) {
                if (entry != null && seed.equals(entry.seed)) {
                    if (entry.cities == null) entry.cities = new ArrayList<>();
                    return entry;
                }
            }
        } catch (Exception ignored) {
            // Treat an unreadable cache as a cache miss.
        }
        return null;
    }

    public static synchronized void save(Entry entry) {
        try {
            FileData data = new FileData();
            if (Files.exists(PATH)) {
                try (Reader reader = Files.newBufferedReader(PATH)) {
                    FileData old = GSON.fromJson(reader, FileData.class);
                    if (old != null && old.entries != null) data.entries = old.entries;
                }
            }
            data.entries.removeIf(old -> old != null && entry.seed.equals(old.seed));
            data.entries.add(entry);

            Files.createDirectories(PATH.getParent());
            Path temp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Cache is an optimization; scanning can continue without it.
        }
    }
}
