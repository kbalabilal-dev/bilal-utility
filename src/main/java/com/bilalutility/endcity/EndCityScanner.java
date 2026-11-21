package com.bilalutility.endcity;

import com.bilalutility.mixin.EndCityShipPieceAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.resource.VanillaResourcePackProvider;
import net.minecraft.structure.EndCityGenerator;
import net.minecraft.structure.StructureSet;
import net.minecraft.structure.StructureSetKeys;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.level.storage.LevelStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Searches only vanilla End City placement candidates and asks vanilla to build the real
 * StructureStart. It does not port or approximate the End City random-piece algorithm.
 */
public final class EndCityScanner {
    public enum State { IDLE, SCANNING, COMPLETE, CANCELLED, ERROR }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BilalUtility-EndCityFinder");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static final int END_SPAWN_X = 100;
    private static final int END_SPAWN_Z = 0;

    private static volatile State state = State.IDLE;
    private static volatile String status = "Ready";
    private static volatile String error = "";
    private static volatile long scanned;
    private static volatile long total;
    private static volatile List<EndCity> results = List.of();

    private EndCityScanner() {}

    public static State state() { return state; }
    public static String status() { return status; }
    public static String error() { return error; }
    public static long scanned() { return scanned; }
    public static long total() { return total; }
    public static List<EndCity> results() { return results; }
    public static boolean isRunning() { return state == State.SCANNING; }

    public static synchronized boolean start(MinecraftClient client, long seed, int centerX, int centerZ,
                                             int radius, int ignoreSpawnRadius) {
        if (isRunning()) return false;
        // Scanning uses a separately loaded vanilla data-pack registry set, so a world
        // does not need to be open. Only the client executor/config environment is required.
        if (client == null) {
            state = State.ERROR;
            error = "Minecraft client is not available.";
            status = error;
            return false;
        }

        CANCEL.set(false);
        state = State.SCANNING;
        error = "";
        status = "Preparing vanilla End generator...";
        scanned = 0;
        total = 0;
        results = List.of();

        // Capture only the data fixer on the client thread. Client-synced registries are
        // intentionally NOT used: they omit server-only worldgen registries such as
        // minecraft:world/noise_settings.
        com.mojang.datafixers.DataFixer dataFixer = client.getDataFixer();

        try {
        WORKER.submit(() -> {
            LevelStorage.Session templateSession = null;
            LifecycledResourceManagerImpl dataResourceManager = null;
            try {
                // An isolated vanilla SERVER_DATA manager makes the search work without a server
                // connection and avoids relying on client assets or server-sent structure data.
                var templateRoot = FabricLoader.getInstance().getConfigDir()
                        .resolve("bilal_utility").resolve("endcity_template_storage");
                LevelStorage storage = LevelStorage.create(templateRoot);
                templateSession = storage.createSession("endcity-finder");
                ResourcePackManager packManager = VanillaDataPackProvider.createManager(templateSession);
                packManager.scanPacks();
                packManager.setEnabledProfiles(List.of(VanillaResourcePackProvider.VANILLA_KEY));
                dataResourceManager = new LifecycledResourceManagerImpl(
                        ResourceType.SERVER_DATA, packManager.createResourcePacks());

                StructureTemplateManager templateManager = new StructureTemplateManager(
                        dataResourceManager, templateSession, dataFixer, Registries.BLOCK);

                // Build the same complete vanilla registry graph used by world loading.
                // DYNAMIC_REGISTRIES contains worldgen settings (noise parameters, biomes,
                // structures, structure sets, etc.); DIMENSION_REGISTRIES contains dimension
                // data such as minecraft:world/noise_settings. Both phases are loaded from the
                // vanilla server-data pack with bound tags instead of the partial client sync.
                List<RegistryWrapper.Impl<?>> staticRegistries = new ArrayList<>();
                Registries.REGISTRIES.forEach(registry -> staticRegistries.add(registry.getReadOnlyWrapper()));

                List<RegistryLoader.Entry<?>> registryEntries = new ArrayList<>();
                registryEntries.addAll(RegistryLoader.DYNAMIC_REGISTRIES);
                registryEntries.addAll(RegistryLoader.DIMENSION_REGISTRIES);

                DynamicRegistryManager.Immutable registryManager = RegistryLoader.loadFromResource(
                        dataResourceManager, staticRegistries, registryEntries);

                // Fail early with a useful message if the requested vanilla data is incomplete.
                registryManager.getOrThrow(RegistryKeys.NOISE_PARAMETERS);
                registryManager.getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
                registryManager.getOrThrow(RegistryKeys.STRUCTURE_SET);

                scan(seed, centerX, centerZ, radius, ignoreSpawnRadius, registryManager, templateManager);
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName() + ": "
                        + (throwable.getMessage() == null ? "Could not prepare vanilla structure data" : throwable.getMessage());
                status = error;
                state = State.ERROR;
            } finally {
                if (dataResourceManager != null) {
                    try { dataResourceManager.close(); } catch (Exception ignored) {}
                }
                if (templateSession != null) {
                    try { templateSession.close(); } catch (Exception ignored) {}
                }
            }
        });
        } catch (RuntimeException rejected) {
            state = State.ERROR;
            error = "Could not start scanner worker: " + rejected.getClass().getSimpleName();
            status = error;
            return false;
        }
        return true;
    }

    public static void cancel() {
        if (isRunning()) {
            CANCEL.set(true);
            status = "Stopping after the current candidate...";
        }
    }

    public static List<EndCityCluster> clusters(int clusterSize, double fromX, double fromZ) {
        if (clusterSize <= 0) clusterSize = 2_000;
        Map<Long, List<EndCity>> buckets = new HashMap<>();
        for (EndCity city : results) {
            int bx = Math.floorDiv(city.x(), clusterSize);
            int bz = Math.floorDiv(city.z(), clusterSize);
            long key = (((long) bx) << 32) ^ (bz & 0xffffffffL);
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(city);
        }

        List<EndCityCluster> out = new ArrayList<>();
        for (List<EndCity> cities : buckets.values()) {
            int x = (int) Math.round(cities.stream().mapToLong(EndCity::x).average().orElse(0));
            int z = (int) Math.round(cities.stream().mapToLong(EndCity::z).average().orElse(0));
            double distance = Math.hypot(x - fromX, z - fromZ);
            out.add(new EndCityCluster(x, z, cities.size(), distance, List.copyOf(cities)));
        }
        out.sort(Comparator.comparingInt(EndCityCluster::count).reversed()
                .thenComparingDouble(EndCityCluster::distance));
        return out;
    }

    public static List<EndCity> nearestFive(double fromX, double fromZ) {
        return results.stream()
                .sorted(Comparator.comparingDouble(city -> Math.hypot(city.x() - fromX, city.z() - fromZ)))
                .limit(5).toList();
    }

    private static void scan(long seed, int centerX, int centerZ, int radius, int ignoreSpawnRadius,
                             DynamicRegistryManager registryManager, StructureTemplateManager templateManager) {
        EndCityCache.Entry cache = EndCityCache.load(Long.toString(seed));
        List<EndCity> found = new ArrayList<>();
        if (cache != null && cache.centerX == centerX && cache.centerZ == centerZ
                && cache.ignoreSpawnRadius == ignoreSpawnRadius && cache.cities != null) {
            for (EndCity city : cache.cities) {
                if (city == null) continue;
                if (squaredDistance(city.x(), city.z(), centerX, centerZ) <= (long) radius * radius
                        && squaredDistance(city.x(), city.z(), END_SPAWN_X, END_SPAWN_Z) >= (long) ignoreSpawnRadius * ignoreSpawnRadius) {
                    found.add(city);
                }
            }
        }

        if (cache != null && cache.complete && cache.centerX == centerX && cache.centerZ == centerZ
                && cache.ignoreSpawnRadius == ignoreSpawnRadius && cache.scanRadius >= radius) {
            results = List.copyOf(found);
            scanned = total = 1;
            state = State.COMPLETE;
            status = "Loaded " + found.size() + " ship cities from cache";
            return;
        }

        try {
            // Use the active world's dynamic registries. BuiltinRegistries.createWrapperLookup()
            // does not have the world's bound tags, which makes structure checks fail with
            // IllegalStateException: tags not bound.
            RegistryWrapper.WrapperLookup lookup = registryManager;
            NoiseConfig noiseConfig = NoiseConfig.create(lookup, ChunkGeneratorSettings.END, seed);
            TheEndBiomeSource biomeSource = TheEndBiomeSource.createVanilla(
                    lookup.getOrThrow(RegistryKeys.BIOME));
            RegistryEntry<ChunkGeneratorSettings> settings = lookup
                    .getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS)
                    .getOrThrow(ChunkGeneratorSettings.END);
            ChunkGenerator generator = new NoiseChunkGenerator(biomeSource, settings);

            StructureSet endCities = lookup.getOrThrow(RegistryKeys.STRUCTURE_SET)
                    .getOrThrow(StructureSetKeys.END_CITIES).value();
            if (!(endCities.placement() instanceof RandomSpreadStructurePlacement placement)) {
                throw new IllegalStateException("Vanilla End City placement is not random spread.");
            }
            if (endCities.structures().isEmpty()) {
                throw new IllegalStateException("The vanilla End City structure set is empty.");
            }

            RegistryEntry<Structure> structureEntry = endCities.structures().get(0).structure();
            Structure structure = structureEntry.value();
            HeightLimitView heightView = HeightLimitView.create(0, 256);

            int cellSize = placement.getSpacing() * 16;
            int paddedRadius = radius + 16;
            int minRegionX = Math.floorDiv(centerX - paddedRadius, cellSize);
            int maxRegionX = Math.floorDiv(centerX + paddedRadius, cellSize);
            int minRegionZ = Math.floorDiv(centerZ - paddedRadius, cellSize);
            int maxRegionZ = Math.floorDiv(centerZ + paddedRadius, cellSize);
            int centerRegionX = Math.floorDiv(centerX, cellSize);
            int centerRegionZ = Math.floorDiv(centerZ, cellSize);
            int maxRing = Math.max(Math.max(Math.abs(minRegionX - centerRegionX), Math.abs(maxRegionX - centerRegionX)),
                    Math.max(Math.abs(minRegionZ - centerRegionZ), Math.abs(maxRegionZ - centerRegionZ)));
            total = (long) (maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);

            Map<Long, EndCity> unique = new LinkedHashMap<>();
            for (EndCity city : found) unique.put((((long) city.x()) << 32) ^ (city.z() & 0xffffffffL), city);
            found = new ArrayList<>(unique.values());
            results = List.copyOf(found);

            long lastCacheSave = 0;
            for (int ring = 0; ring <= maxRing && !CANCEL.get(); ring++) {
                for (int dz = -ring; dz <= ring && !CANCEL.get(); dz++) {
                    for (int side : (ring == 0 ? new int[]{0} : new int[]{-ring, ring})) {
                        int rx = centerRegionX + side;
                        int rz = centerRegionZ + dz;
                        if (rx < minRegionX || rx > maxRegionX || rz < minRegionZ || rz > maxRegionZ) continue;
                        processRegion(seed, centerX, centerZ, radius, ignoreSpawnRadius, rx, rz,
                                placement, generator, noiseConfig, heightView, structure, structureEntry,
                                registryManager, templateManager, found, unique);
                        scanned++;
                        updateProgressStatus(found.size());
                        if ((scanned & 0x1FF) == 0) Thread.yield();
                        if (found.size() != lastCacheSave && found.size() % 8 == 0) {
                            lastCacheSave = found.size();
                            publishAndSave(seed, centerX, centerZ, radius, ignoreSpawnRadius, found, false);
                        }
                    }
                }
                for (int dx = -ring + 1; dx <= ring - 1 && !CANCEL.get(); dx++) {
                    for (int side : new int[]{-ring, ring}) {
                        int rx = centerRegionX + dx;
                        int rz = centerRegionZ + side;
                        if (rx < minRegionX || rx > maxRegionX || rz < minRegionZ || rz > maxRegionZ) continue;
                        processRegion(seed, centerX, centerZ, radius, ignoreSpawnRadius, rx, rz,
                                placement, generator, noiseConfig, heightView, structure, structureEntry,
                                registryManager, templateManager, found, unique);
                        scanned++;
                        updateProgressStatus(found.size());
                        if ((scanned & 0x1FF) == 0) Thread.yield();
                        if (found.size() != lastCacheSave && found.size() % 8 == 0) {
                            lastCacheSave = found.size();
                            publishAndSave(seed, centerX, centerZ, radius, ignoreSpawnRadius, found, false);
                        }
                    }
                }
            }

            publishAndSave(seed, centerX, centerZ, radius, ignoreSpawnRadius, found, !CANCEL.get());
            if (CANCEL.get()) {
                state = State.CANCELLED;
                status = "Scan stopped — " + found.size() + " ship cities saved";
            } else {
                state = State.COMPLETE;
                status = "Complete — found " + found.size() + " ship cities";
            }
        } catch (Throwable throwable) {
            error = throwable.getClass().getSimpleName() + ": "
                    + (throwable.getMessage() == null ? "Unknown scan error" : throwable.getMessage());
            status = error;
            state = State.ERROR;
            publishAndSave(seed, centerX, centerZ, radius, ignoreSpawnRadius, found, false);
        }
    }

    private static void processRegion(long seed, int centerX, int centerZ, int radius, int ignoreSpawnRadius,
                                      int regionX, int regionZ, RandomSpreadStructurePlacement placement,
                                      ChunkGenerator generator, NoiseConfig noiseConfig, HeightLimitView heightView,
                                      Structure structure, RegistryEntry<Structure> structureEntry,
                                      DynamicRegistryManager registryManager, StructureTemplateManager templateManager,
                                      List<EndCity> found, Map<Long, EndCity> unique) {
        if (CANCEL.get()) return;

        // Pass the region's minimum chunk coordinate; vanilla performs the exact region RNG internally.
        ChunkPos startChunk = placement.getStartChunk(seed, regionX * placement.getSpacing(),
                regionZ * placement.getSpacing());
        int candidateX = startChunk.getCenterX();
        int candidateZ = startChunk.getCenterZ();

        long paddedRadius = (long) radius + 16L;
        if (squaredDistance(candidateX, candidateZ, centerX, centerZ) > paddedRadius * paddedRadius) return;

        // Ask the real vanilla structure to perform its exact position/height/biome check first.
        // This uses the same deterministic Structure.Context as createStructureStart, but avoids
        // generating all End City pieces for candidates that fail the vanilla Y>=60 check.
        Structure.Context context = new Structure.Context(registryManager, generator, generator.getBiomeSource(),
                noiseConfig, templateManager, seed, startChunk, heightView, structure.getValidBiomes()::contains);
        var structurePosition = structure.getValidStructurePosition(context);
        if (structurePosition.isEmpty()) return;

        BlockPos exactPosition = structurePosition.get().position();
        int cityX = exactPosition.getX();
        int cityZ = exactPosition.getZ();
        if (squaredDistance(cityX, cityZ, centerX, centerZ) > (long) radius * radius) return;
        if (squaredDistance(cityX, cityZ, END_SPAWN_X, END_SPAWN_Z) < (long) ignoreSpawnRadius * ignoreSpawnRadius) return;

        StructureStart start = structure.createStructureStart(structureEntry, World.END, registryManager,
                generator, generator.getBiomeSource(), noiseConfig, templateManager, seed, startChunk, 0,
                heightView, structure.getValidBiomes()::contains);
        if (!start.hasChildren()) return;

        for (var piece : start.getChildren()) {
            if (piece instanceof EndCityGenerator.Piece) {
                String template = ((EndCityShipPieceAccessor) piece).bilal$getTemplateIdString();
                if ("end_city/ship".equals(template)) {
                    long key = (((long) cityX) << 32) ^ (cityZ & 0xffffffffL);
                    if (!unique.containsKey(key)) {
                        EndCity city = new EndCity(cityX, cityZ);
                        unique.put(key, city);
                        found.add(city);
                    }
                    return;
                }
            }
        }
    }

    private static void updateProgressStatus(int cityCount) {
        if (scanned == 1 || scanned % 2048 == 0) {
            long count = total <= 0 ? 0 : Math.min(100, scanned * 100 / total);
            status = "Scanning " + count + "% • " + cityCount + " ship cities found";
        }
    }

    private static long squaredDistance(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private static void publishAndSave(long seed, int centerX, int centerZ, int radius, int ignoreSpawnRadius,
                                       List<EndCity> found, boolean complete) {
        results = List.copyOf(found);
        EndCityCache.Entry entry = new EndCityCache.Entry();
        entry.seed = Long.toString(seed);
        entry.centerX = centerX;
        entry.centerZ = centerZ;
        entry.scanRadius = radius;
        entry.ignoreSpawnRadius = ignoreSpawnRadius;
        entry.complete = complete;
        entry.cities = new ArrayList<>(found);
        EndCityCache.save(entry);
    }
}
