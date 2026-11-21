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
import net.minecraft.registry.tag.TagGroupLoader;
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
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.CheckedRandom;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
            WORKER.submit(() -> runWithEnv(seed, dataFixer, (registryManager, templateManager) ->
                    scan(seed, centerX, centerZ, radius, ignoreSpawnRadius, registryManager, templateManager)));
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


    // ------------------------------------------------------------------------------------------
    // Environment shared by all worker threads (vanilla objects are thread-safe for reads).
    // ------------------------------------------------------------------------------------------
    private record Env(long seed, DynamicRegistryManager registryManager, StructureTemplateManager templateManager,
                       NoiseConfig noiseConfig, ChunkGenerator generator, RandomSpreadStructurePlacement placement,
                       RegistryEntry<Structure> structureEntry, Structure structure, HeightLimitView heightView,
                       SimplexNoiseSampler islandNoise) {}

    private static Env buildEnv(long seed, DynamicRegistryManager registryManager, StructureTemplateManager templateManager) {
        RegistryWrapper.WrapperLookup lookup = registryManager;
        NoiseConfig noiseConfig = NoiseConfig.create(lookup, ChunkGeneratorSettings.END, seed);
        TheEndBiomeSource biomeSource = TheEndBiomeSource.createVanilla(lookup.getOrThrow(RegistryKeys.BIOME));
        RegistryEntry<ChunkGeneratorSettings> settings = lookup
                .getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS).getOrThrow(ChunkGeneratorSettings.END);
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

        // Same seeding vanilla uses for the End island noise: skip 17292 values, then build the sampler.
        CheckedRandom random = new CheckedRandom(seed);
        for (int i = 0; i < 17292; i++) random.nextInt();
        SimplexNoiseSampler islandNoise = new SimplexNoiseSampler(random);

        return new Env(seed, registryManager, templateManager, noiseConfig, generator, placement, structureEntry,
                structureEntry.value(), HeightLimitView.create(0, 256), islandNoise);
    }

    // ------------------------------------------------------------------------------------------
    // Cheap pre-filter. This is a port of vanilla's End islands density term (only used to SKIP
    // candidates that clearly have no terrain near y=60; survivors still go through vanilla's own
    // exact check, so it can only cost speed or (if mis-tuned) miss a city, never invent one).
    // Returns the highest island value found around the candidate's 4 height-check points.
    // ------------------------------------------------------------------------------------------
    private static float islandScore(SimplexNoiseSampler noise, int cx, int cz) {
        // Height-check points are within 5 blocks of (cx, cz); noise cells are 8 blocks wide.
        int cellMinX = Math.floorDiv(cx - 6, 8), cellMaxX = Math.floorDiv(cx + 6, 8) + 1;
        int cellMinZ = Math.floorDiv(cz - 6, 8), cellMaxZ = Math.floorDiv(cz + 6, 8) + 1;
        int nx = cellMaxX - cellMinX + 1, nz = cellMaxZ - cellMinZ + 1;
        float[] f = new float[nx * nz];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int x = cellMinX + i, z = cellMinZ + j;
                f[i * nz + j] = Math.max(-100.0F, Math.min(80.0F, 100.0F - (float) Math.sqrt((long) x * x + (long) z * z) * 8.0F));
            }
        }
        // Vanilla: for cell x the island centres q satisfy |q - trunc(x/2)| <= 12; h = x - 2q.
        int gx0 = Math.floorDiv(cellMinX, 2) - 13, gx1 = Math.floorDiv(cellMaxX, 2) + 13;
        int gz0 = Math.floorDiv(cellMinZ, 2) - 13, gz1 = Math.floorDiv(cellMaxZ, 2) + 13;
        for (long o = gx0; o <= gx1; o++) {
            for (long p = gz0; p <= gz1; p++) {
                if (o * o + p * p <= 4096L) continue;
                if (noise.sample((double) o, (double) p) >= -0.9F) continue;
                float g = (Math.abs((float) o) * 3439.0F + Math.abs((float) p) * 147.0F) % 13.0F + 9.0F;
                for (int i = 0; i < nx; i++) {
                    int x = cellMinX + i;
                    if (Math.abs(o - x / 2) > 12) continue;
                    for (int j = 0; j < nz; j++) {
                        int z = cellMinZ + j;
                        if (Math.abs(p - z / 2) > 12) continue;
                        float h = (float) (x - 2 * o);
                        float q = (float) (z - 2 * p);
                        float r = 100.0F - (float) Math.sqrt(h * h + q * q) * g;
                        r = Math.max(-100.0F, Math.min(80.0F, r));
                        if (r > f[i * nz + j]) f[i * nz + j] = r;
                    }
                }
            }
        }
        float best = -100.0F;
        for (float v : f) if (v > best) best = v;
        return best;
    }

    // ---- filter calibration (learned from vanilla results of this very seed) ----
    private static final int CALIBRATION_PASSES = 40;
    private static final float FILTER_MARGIN = 14.0F;
    private static final AtomicInteger calibPasses = new AtomicInteger();
    private static volatile float filterThreshold = Float.NEGATIVE_INFINITY;   // -inf = filter off
    private static volatile boolean useFilter = true;
    private static final Object CALIB_LOCK = new Object();
    private static float calibMin = Float.POSITIVE_INFINITY;

    private static void resetCalibration() {
        calibPasses.set(0);
        synchronized (CALIB_LOCK) { calibMin = Float.POSITIVE_INFINITY; }
        filterThreshold = Float.NEGATIVE_INFINITY;
    }

    private static void recordPass(float score) {
        if (calibPasses.get() >= CALIBRATION_PASSES && filterThreshold > Float.NEGATIVE_INFINITY) return;
        synchronized (CALIB_LOCK) {
            if (score < calibMin) calibMin = score;
            if (calibPasses.incrementAndGet() >= CALIBRATION_PASSES) {
                filterThreshold = calibMin - FILTER_MARGIN;
            }
        }
    }

    public static boolean useFilter() { return useFilter; }
    public static void setUseFilter(boolean on) { useFilter = on; }

    private static int threadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores - 1));
    }

    // ------------------------------------------------------------------------------------------
    // The scan
    // ------------------------------------------------------------------------------------------
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

        ExecutorService pool = Executors.newFixedThreadPool(threadCount(), r -> {
            Thread t = new Thread(r, "BilalUtility-EndCityScan");
            t.setDaemon(true);
            return t;
        });
        try {
            Env env = buildEnv(seed, registryManager, templateManager);
            RandomSpreadStructurePlacement placement = env.placement();

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
            final List<EndCity> foundList = found;

            resetCalibration();
            status = "Scanning 0%";
            int lastSaved = foundList.size();
            long lastSaveTime = System.currentTimeMillis();

            for (int ring = 0; ring <= maxRing && !CANCEL.get(); ring++) {
                // all regions of this ring
                int[] rx = new int[Math.max(1, 8 * ring)];
                int[] rz = new int[rx.length];
                int n = 0;
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        int x = centerRegionX + dx, z = centerRegionZ + dz;
                        if (x < minRegionX || x > maxRegionX || z < minRegionZ || z > maxRegionZ) continue;
                        rx[n] = x;
                        rz[n] = z;
                        n++;
                    }
                }
                if (n == 0) continue;

                int threads = n < 64 ? 1 : threadCount();
                List<Future<?>> tasks = new ArrayList<>();
                final int count = n;
                for (int t = 0; t < threads; t++) {
                    final int offset = t, step = threads;
                    tasks.add(pool.submit((Callable<Void>) () -> {
                        for (int i = offset; i < count && !CANCEL.get(); i += step) {
                            processRegion(env, centerX, centerZ, radius, ignoreSpawnRadius, rx[i], rz[i], foundList, unique);
                            scanned++;   // approximate under threads; only used for the progress bar
                        }
                        return null;
                    }));
                }
                for (Future<?> f : tasks) f.get();

                updateProgressStatus(foundList.size());
                long now = System.currentTimeMillis();
                if (foundList.size() != lastSaved && now - lastSaveTime > 5000) {
                    synchronized (foundList) { publishAndSave(seed, centerX, centerZ, radius, ignoreSpawnRadius, foundList, false); }
                    lastSaved = foundList.size();
                    lastSaveTime = now;
                }
            }

            synchronized (foundList) { publishAndSave(seed, centerX, centerZ, radius, ignoreSpawnRadius, foundList, !CANCEL.get()); }
            if (CANCEL.get()) {
                state = State.CANCELLED;
                status = "Scan stopped — " + foundList.size() + " ship cities saved";
            } else {
                state = State.COMPLETE;
                status = "Complete — found " + foundList.size() + " ship cities";
            }
        } catch (Throwable throwable) {
            error = throwable.getClass().getSimpleName() + ": "
                    + (throwable.getMessage() == null ? "Unknown scan error" : throwable.getMessage());
            status = error;
            state = State.ERROR;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Result of checking one region's candidate chunk.
     * 0 = rejected cheaply, 1 = rejected by vanilla (no valid start), 2 = valid start but no ship, 3 = ship city.
     */
    private static int checkRegion(Env env, int centerX, int centerZ, int radius, int ignoreSpawnRadius,
                                   int regionX, int regionZ, boolean allowFilter, float[] scoreOut, int[] posOut) {
        RandomSpreadStructurePlacement placement = env.placement();
        // vanilla does the exact region RNG internally
        ChunkPos startChunk = placement.getStartChunk(env.seed(), regionX * placement.getSpacing(),
                regionZ * placement.getSpacing());
        int candidateX = startChunk.getCenterX();
        int candidateZ = startChunk.getCenterZ();

        // 1. free checks first: scan area and ignore area
        long paddedRadius = (long) radius + 16L;
        if (squaredDistance(candidateX, candidateZ, centerX, centerZ) > paddedRadius * paddedRadius) return 0;
        long ignorePad = Math.max(0L, (long) ignoreSpawnRadius - 32L);
        if (squaredDistance(candidateX, candidateZ, END_SPAWN_X, END_SPAWN_Z) < ignorePad * ignorePad) return 0;

        // 2. cheap island filter (only once it has learned a threshold from vanilla results)
        float score = islandScore(env.islandNoise(), candidateX, candidateZ);
        scoreOut[0] = score;
        if (allowFilter && score < filterThreshold) return 0;

        // 3. exact vanilla position / height / biome check
        Structure structure = env.structure();
        Structure.Context context = new Structure.Context(env.registryManager(), env.generator(),
                env.generator().getBiomeSource(), env.noiseConfig(), env.templateManager(), env.seed(), startChunk,
                env.heightView(), structure.getValidBiomes()::contains);
        var structurePosition = structure.getValidStructurePosition(context);
        if (structurePosition.isEmpty()) return 1;

        BlockPos exactPosition = structurePosition.get().position();
        int cityX = exactPosition.getX();
        int cityZ = exactPosition.getZ();
        posOut[0] = cityX;
        posOut[1] = cityZ;
        if (squaredDistance(cityX, cityZ, centerX, centerZ) > (long) radius * radius) return 1;
        if (squaredDistance(cityX, cityZ, END_SPAWN_X, END_SPAWN_Z) < (long) ignoreSpawnRadius * ignoreSpawnRadius) return 1;

        // 4. the real StructureStart; look for the ship piece
        StructureStart start = structure.createStructureStart(env.structureEntry(), World.END, env.registryManager(),
                env.generator(), env.generator().getBiomeSource(), env.noiseConfig(), env.templateManager(), env.seed(),
                startChunk, 0, env.heightView(), structure.getValidBiomes()::contains);
        if (!start.hasChildren()) return 1;
        for (var piece : start.getChildren()) {
            if (piece instanceof EndCityGenerator.Piece) {
                String template = ((EndCityShipPieceAccessor) piece).bilal$getTemplateIdString();
                if ("end_city/ship".equals(template)) return 3;
            }
        }
        return 2;
    }

    private static void processRegion(Env env, int centerX, int centerZ, int radius, int ignoreSpawnRadius,
                                      int regionX, int regionZ, List<EndCity> found, Map<Long, EndCity> unique) {
        if (CANCEL.get()) return;
        float[] score = new float[1];
        int[] pos = new int[2];
        boolean learned = filterThreshold > Float.NEGATIVE_INFINITY;
        int r = checkRegion(env, centerX, centerZ, radius, ignoreSpawnRadius, regionX, regionZ,
                useFilter && learned, score, pos);
        if (r >= 2) recordPass(score[0]);   // any valid start teaches the filter
        if (r != 3) return;
        long key = (((long) pos[0]) << 32) ^ (pos[1] & 0xffffffffL);
        synchronized (found) {
            if (!unique.containsKey(key)) {
                EndCity city = new EndCity(pos[0], pos[1]);
                unique.put(key, city);
                found.add(city);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Verify: learns the filter on one area, then checks a different area with and without it.
    // ------------------------------------------------------------------------------------------
    public static synchronized boolean startVerify(MinecraftClient client, long seed) {
        if (isRunning()) return false;
        if (client == null) return false;
        CANCEL.set(false);
        state = State.SCANNING;
        error = "";
        status = "Verify: preparing vanilla generator...";
        scanned = 0;
        total = 0;
        com.mojang.datafixers.DataFixer dataFixer = client.getDataFixer();
        WORKER.submit(() -> runWithEnv(seed, dataFixer, (registryManager, templateManager) -> {
            Env env = buildEnv(seed, registryManager, templateManager);
            resetCalibration();
            int spacing = env.placement().getSpacing();
            int span = 36;   // 36 x 36 regions per window (about 1300 candidates)
            float[] sc = new float[1];
            int[] pos = new int[2];
            int calibPass = 0;
            // window A: learn
            int ax = Math.floorDiv(40_000, spacing * 16), az = 0;
            status = "Verify: learning filter (window A)...";
            total = (long) span * span * 2;
            for (int i = 0; i < span && !CANCEL.get(); i++) {
                for (int j = 0; j < span && !CANCEL.get(); j++) {
                    int r = checkRegion(env, 0, 0, 400_000, 0, ax + i, az + j, false, sc, pos);
                    if (r >= 2) { recordPass(sc[0]); calibPass++; }
                    scanned++;
                }
            }
            if (filterThreshold == Float.NEGATIVE_INFINITY) {
                status = "Verify: only " + calibPass + " valid cities in window A, need " + CALIBRATION_PASSES
                        + ". Filter stays off (scan still exact, just slower).";
                state = State.COMPLETE;
                return;
            }
            // window B: compare
            int bx = Math.floorDiv(70_000, spacing * 16), bz = Math.floorDiv(30_000, spacing * 16);
            status = "Verify: comparing (window B)...";
            int valid = 0, missed = 0, ships = 0, skipped = 0;
            long tFull = 0, tFilt = 0;
            for (int i = 0; i < span && !CANCEL.get(); i++) {
                for (int j = 0; j < span && !CANCEL.get(); j++) {
                    long t0 = System.nanoTime();
                    int exact = checkRegion(env, 0, 0, 400_000, 0, bx + i, bz + j, false, sc, pos);
                    long t1 = System.nanoTime();
                    int filt = checkRegion(env, 0, 0, 400_000, 0, bx + i, bz + j, true, sc, pos);
                    long t2 = System.nanoTime();
                    tFull += t1 - t0;
                    tFilt += t2 - t1;
                    if (exact >= 2) valid++;
                    if (exact == 3) ships++;
                    if (filt == 0 && exact >= 2) missed++;
                    if (filt == 0 && exact < 2) skipped++;
                    scanned++;
                }
            }
            double speedup = tFilt <= 0 ? 0 : (double) tFull / tFilt;
            status = "Verify done: " + valid + " valid cities, " + ships + " with ships, filter missed " + missed
                    + (missed == 0 ? " (OK)" : " (BAD: turn filter off)")
                    + ". Filter skipped " + skipped + " empty spots, ~" + String.format(java.util.Locale.ROOT, "%.1f", speedup) + "x faster.";
            if (missed > 0) useFilter = false;
            state = State.COMPLETE;
        }));
        return true;
    }

    private interface EnvTask { void run(DynamicRegistryManager registryManager, StructureTemplateManager templateManager) throws Exception; }

    /** Loads the vanilla data once for a task. Same loading code the scan uses. */
    private static void runWithEnv(long seed, com.mojang.datafixers.DataFixer dataFixer, EnvTask task) {
        LevelStorage.Session templateSession = null;
        LifecycledResourceManagerImpl dataResourceManager = null;
        try {
            var templateRoot = FabricLoader.getInstance().getConfigDir()
                    .resolve("bilal_utility").resolve("endcity_template_storage");
            LevelStorage storage = LevelStorage.create(templateRoot);
            templateSession = storage.createSession("endcity-finder");
            ResourcePackManager packManager = VanillaDataPackProvider.createManager(templateSession);
            packManager.scanPacks();
            packManager.setEnabledProfiles(List.of(VanillaResourcePackProvider.VANILLA_KEY));
            dataResourceManager = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA, packManager.createResourcePacks());
            StructureTemplateManager templateManager = new StructureTemplateManager(
                    dataResourceManager, templateSession, dataFixer, Registries.BLOCK);

            // Same call vanilla's SaveLoading uses: static registries with their tags bound from the data pack.
            List<RegistryWrapper.Impl<?>> staticRegistries = new ArrayList<>();
            for (var pendingTags : TagGroupLoader.startReload(
                    dataResourceManager, DynamicRegistryManager.of(Registries.REGISTRIES))) {
                staticRegistries.add(pendingTags.getLookup());   // lookup with tags loaded; apply() is NOT called
            }
            List<RegistryLoader.Entry<?>> registryEntries = new ArrayList<>();
            registryEntries.addAll(RegistryLoader.DYNAMIC_REGISTRIES);
            registryEntries.addAll(RegistryLoader.DIMENSION_REGISTRIES);
            DynamicRegistryManager.Immutable registryManager = RegistryLoader.loadFromResource(
                    dataResourceManager, staticRegistries, registryEntries);
            registryManager.getOrThrow(RegistryKeys.NOISE_PARAMETERS);
            registryManager.getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
            registryManager.getOrThrow(RegistryKeys.STRUCTURE_SET);
            task.run(registryManager, templateManager);
        } catch (Throwable throwable) {
            error = throwable.getClass().getSimpleName() + ": "
                    + (throwable.getMessage() == null ? "Could not prepare vanilla structure data" : throwable.getMessage());
            status = error;
            state = State.ERROR;
        } finally {
            if (dataResourceManager != null) { try { dataResourceManager.close(); } catch (Exception ignored) {} }
            if (templateSession != null) { try { templateSession.close(); } catch (Exception ignored) {} }
        }
    }

    private static void updateProgressStatus(int cityCount) {
        long count = total <= 0 ? 0 : Math.min(100, scanned * 100 / total);
        status = "Scanning " + count + "% • " + cityCount + " ship cities found"
                + (filterThreshold > Float.NEGATIVE_INFINITY && useFilter ? " • fast filter on" : " • learning filter");
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
