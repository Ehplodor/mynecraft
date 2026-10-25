package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.Colormap;
import net.mynecraft.resource.FontLoader;
import net.mynecraft.resource.PackSource;
import net.mynecraft.resource.ResourceManager;
import net.mynecraft.world.weather.Atmosphere;
import net.mynecraft.world.weather.Weather;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkStreamer;
import net.mynecraft.world.storage.WorldStorage;
import net.mynecraft.world.FluidSimulator;
import net.mynecraft.world.crafting.CraftingMenu;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.Survival;
import net.mynecraft.world.storage.PlayerData;
import net.mynecraft.world.Placement;
import net.mynecraft.world.Player;
import net.mynecraft.world.Raycast;
import net.mynecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

public final class Main {
    private static final int RENDER_RADIUS = 12; // chunks
    private static final double PHYSICS_STEP = 1 / 120.0, TICK = 1 / 20.0;
    private static final double REACH = 5;

    private static final long AUTOSAVE_NANOS = 30_000_000_000L;
    private static final double BENCH_SPEED = 20; // blocks per second, about elytra cruising

    private static String option(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    /** Height (0..1) within the hit block of the point where the ray enters it through a side face. */
    private static double hitHeight(Raycast.Hit hit, double ox, double oy, double oz, double dx, double dy, double dz) {
        double t;
        if (hit.nx() != 0) t = (hit.x() + (hit.nx() > 0 ? 1 : 0) - ox) / dx;
        else if (hit.nz() != 0) t = (hit.z() + (hit.nz() > 0 ? 1 : 0) - oz) / dz;
        else return hit.ny() > 0 ? 1 : 0;
        return oy + t * dy - hit.y();
    }

    private static final String[] FACING = {"sud (+Z)", "ouest (-X)", "nord (-Z)", "est (+X)"};

    private static final String[] PRECIPITATION = {"sec", "pluie", "neige fondue", "neige", "grêle"};

    private static String compass(float x, float z) {
        String[] names = {"l'est", "le sud-est", "le sud", "le sud-ouest", "l'ouest", "le nord-ouest", "le nord", "le nord-est"};
        return names[Math.floorMod(Math.round((float) (Math.atan2(z, x) / (Math.PI / 4))), 8)];
    }

    /**
     * Bad weather on screen: an overcast sky turns grey and dims the daylight, fog and falling
     * precipitation pull the fog distance in, and lightning flashes light everything up for an instant.
     */
    private static void applyWeather(Atmosphere.Sample w, float flash, float[] sky, WorldRenderer renderer) {
        float wet = Math.min(1, w.precipitation() / 0.03f);
        float overcast = Math.min(1, Math.max(0, w.cloud() - 0.1f) * 0.8f + wet * 0.6f);
        float d = renderer.daylight;
        renderer.daylight = Math.min(1, Math.max(0.15f, d * (1 - 0.4f * overcast)) + flash * 0.5f);
        float grey = 0.12f + 0.55f * (d - 0.15f) / 0.85f * (1 - 0.35f * w.storm());
        for (int i = 0; i < 3; i++) sky[i] = Math.min(1, sky[i] + ((grey + i * 0.02f) - sky[i]) * overcast * 0.85f + flash * 0.4f);
        float thick = w.type() == Atmosphere.Precipitation.SNOW ? 0.6f : 0.35f;
        renderer.fogScale = Math.max(0.12f, 1 - 0.85f * w.fog() - thick * wet);
    }

    /** F3 screen: the figures needed to judge performance live, plus position and target. */
    private static void debugScreen(Hud hud, World world, WorldRenderer renderer, ChunkStreamer streamer, Player player,
                                    Camera cam, Raycast.Hit hit, double ticks, int fps, double frameMillis, double gpuMillis,
                                    int fluidsPending, double fluidRate, boolean settling, Atmosphere.Sample w) {
        Runtime rt = Runtime.getRuntime();
        long[] buf = renderer.bufferBytes();
        int bx = (int) Math.floor(player.x), by = (int) Math.floor(cam.pos.y), bz = (int) Math.floor(player.z);
        int light = world.getLight(bx, by, bz);
        double yawDeg = Math.toDegrees(cam.yaw);
        int quarter = Math.floorMod((int) Math.round(-yawDeg / 90.0) + 2, 4);
        String target = "rien";
        if (hit != null) {
            byte b = world.get(hit.x(), hit.y(), hit.z());
            target = Blocks.NAMES[b] + (Blocks.STATE[b].isEmpty() ? "" : "[" + Blocks.STATE[b] + "]")
                + String.format(" @ %d %d %d", hit.x(), hit.y(), hit.z());
        }
        String[] lines = {
            String.format("Mynecraft | %d FPS (%.1f ms/image) | GPU %.2f ms", fps, frameMillis, gpuMillis),
            String.format("XYZ : %.2f / %.2f / %.2f | chunk %d %d | section %d", player.x, player.y, player.z,
                bx >> 4, bz >> 4, Chunk.sectionIndex(by)),
            String.format("Regard : %s | lacet %d° tangage %.0f°", FACING[quarter],
                Math.floorMod((long) Math.round(yawDeg), 360), Math.toDegrees(cam.pitch)),
            "Bloc visé : " + target,
            String.format("Lumière : ciel %d | bloc %d", light >> 4, light & 15),
            String.format("Biome : %s | température %.2f | humidité %.2f%s",
                Landscape.BIOME_NAMES[world.generator.column(bx, bz).biome()], world.generator.temperature(bx, bz),
                world.generator.downfall(bx, bz), world.generator.column(bx, bz).river() > 0
                    ? String.format(" | rivière : débit %.0f, surface %d", world.generator.landscape().hydrology().flow(bx, bz),
                        world.generator.column(bx, bz).water()) : ""),
            String.format("Météo : %.1f °C | humidité %d %% | nuages %.2f g/kg (base %d) | %s %.3f",
                w.temperature(), Math.round(w.relativeHumidity() * 100), w.cloud(), w.cloudBase(),
                PRECIPITATION[w.type().ordinal()], w.precipitation()),
            String.format("Vent : %.1f blocs/s vers %s | %d hPa | brouillard %d %% | orage %d %%",
                Math.hypot(w.windX(), w.windZ()), compass(w.windX(), w.windZ()), w.hectopascals(),
                Math.round(w.fog() * 100), Math.round(w.storm() * 100)),
            String.format("Heure : %d ticks | jour %d", (long) ticks % DayCycle.DAY_TICKS, (long) ticks / DayCycle.DAY_TICKS),
            String.format("Sections : %d dessinées / %d maillées | %d quads", renderer.drawnSections,
                renderer.meshCount(), renderer.totalQuads),
            String.format("Chunks : %d chargés | %d en chargement", world.loadedChunks().size(), streamer.loadingCount()),
            String.format("Fluides : %d en attente | %.0f mises à jour/s%s", fluidsPending, fluidRate,
                settling ? " | pré-calcul accéléré" : ""),
            String.format("Mémoire : tas %d/%d Mio | GPU %.1f/%.1f Mio | blocs+lumière %.1f Mio",
                (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, buf[0] / 1048576.0,
                buf[1] / 1048576.0, world.storageBytes() / 1048576.0),
        };
        float y = 2 * hud.scale;
        for (String line : lines) {
            hud.rect(hud.scale, y - hud.scale, hud.textWidth(line) + 2 * hud.scale, hud.lineHeight(), 0, 0, 0, 0.45f);
            hud.text(line, 2 * hud.scale, y, 1, 1, 1);
            y += hud.lineHeight();
        }
    }

    private static void demoSet(World world, WorldRenderer renderer, int x, int y, int z, byte b) {
        if (world.set(x, y, z, b)) renderer.blockChanged(x, y, z);
    }

    public static void main(String[] args) {
        java.util.List<String> argv = java.util.Arrays.asList(args);
        boolean vsync = !argv.contains("--no-vsync") && !argv.contains("--benchmark");
        int shotIdx = argv.indexOf("--screenshot");
        String screenshot = shotIdx >= 0 && shotIdx + 1 < args.length ? args[shotIdx + 1] : null;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        long window = glfwCreateWindow(1280, 720, "Mynecraft", 0, 0);
        if (window == 0) throw new IllegalStateException("Window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        ResourceManager resources = new ResourceManager().add(PackSource.classpath("defaultpack"));
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--pack")) {
                try {
                    resources.add(PackSource.open(Path.of(args[i + 1])));
                } catch (IOException e) {
                    System.err.println("Cannot open pack " + args[i + 1] + ": " + e.getMessage());
                }
            }
        }
        BlockAppearance appearance = new BlockAppearance(resources);
        TextureArray textures = new TextureArray(appearance, resources);
        appearance.warnings.forEach(w -> System.err.println("[resources] " + w));
        textures.warnings.forEach(w -> System.err.println("[resources] " + w));

        Path worldDir = Path.of(option(args, "--world", "saves/world"));
        long newSeed = Long.parseLong(option(args, "--seed", Long.toString(new java.util.Random().nextLong())));
        WorldStorage storage;
        try {
            storage = WorldStorage.open(worldDir, newSeed, argv.contains("--allow-foreign-writes"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open world " + worldDir, e);
        }
        if (storage.readOnly) {
            System.err.println("[storage] " + worldDir + " was not created by Mynecraft: opened READ-ONLY."
                + " Use --allow-foreign-writes to save into it (blocks outside y 0..127, entities and"
                + " block properties would be lost).");
        }
        World world = new World(storage.seed);
        BiomeMap biomes = new BiomeMap(world.generator,
            Colormap.load(resources, "grass", 0x91BD59), Colormap.load(resources, "foliage", 0x77AB2F));
        WorldRenderer renderer = new WorldRenderer(world, appearance.faceInfo, textures, biomes, RENDER_RADIUS + 1);
        renderer.occlusion = !argv.contains("--no-occlusion");
        renderer.measureFrustum = screenshot != null;
        if (argv.contains("--fullbright")) renderer.minLight = 1;
        // Unsettled fluids of newly visible chunks (springs, hanging water), for the fluid simulation.
        java.util.concurrent.ConcurrentLinkedQueue<int[]> springs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ChunkStreamer streamer = new ChunkStreamer(world, storage, RENDER_RADIUS,
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), new ChunkStreamer.Listener() {
                public void chunkVisible(int cx, int cz) {
                    renderer.chunkVisible(cx, cz);
                    Chunk c = world.chunk(cx, cz);
                    int[] fluids = c == null ? null : c.unsettledFluids;
                    if (fluids != null) {
                        c.unsettledFluids = null;
                        springs.add(fluids);
                    }
                }
                public void chunkHidden(int cx, int cz) { renderer.chunkHidden(cx, cz); }
            });

        boolean editDemo = argv.contains("--edit-demo"), fluidDemo = argv.contains("--fluid-demo");
        Overlay overlay = new Overlay();
        FontLoader font = FontLoader.load(resources);
        if (font == null) System.err.println("[resources] no bitmap font in font/default.json: text disabled");
        Hud hud = new Hud(appearance.faceInfo, font, resources);
        GpuTimer gpuTimer = new GpuTimer();
        boolean paused = argv.contains("--paused"), debug = argv.contains("--f3"); // also for scripted shots
        byte shownBlock = -1;
        long blockShownAt = 0;
        int fps = 0;
        double frameMillis = 0;
        SkyRenderer skyRenderer = new SkyRenderer(resources);
        org.joml.Matrix4f skyViewProj = new org.joml.Matrix4f();
        Camera cam = new Camera();
        Player player = new Player();
        if (storage.playerPos != null) {
            player.x = storage.playerPos[0];
            player.y = Math.max(Chunk.MIN_Y + 1, Math.min(Chunk.MAX_Y - 2, storage.playerPos[1]));
            player.z = storage.playerPos[2];
        } else {
            int[] spawn = world.generator.findSpawn(8, 8);
            player.x = spawn[0] + 0.5;
            player.y = spawn[1];
            player.z = spawn[2] + 0.5;
        }
        // Survival state: vanilla game mode, health, air and inventory from level.dat.
        if (storage.spawn == null) storage.spawn = world.generator.findSpawn(8, 8); // dry land, not a glacier
        PlayerData playerData = storage.player != null ? storage.player : new PlayerData();
        boolean creative = argv.contains("--creative") || (playerData.creative && !argv.contains("--survival"));
        Inventory inventory = playerData.inventory;
        Survival survival = new Survival();
        survival.health = playerData.health;
        survival.air = playerData.air;
        survival.dead = survival.health == 0;
        int[] mining = null; // block being mined in survival
        double miningProgress = 0;
        long hurtFlashUntil = 0;
        // Data packs (recipes, tags): built-in, the world's datapacks/ folder (vanilla), then --datapack.
        java.util.List<PackSource> dataPacks = new java.util.ArrayList<>();
        dataPacks.add(PackSource.classpath("defaultdata"));
        try (var dir = java.nio.file.Files.list(worldDir.resolve("datapacks"))) {
            for (Path p : dir.sorted().toList()) dataPacks.add(PackSource.open(p));
        } catch (IOException ignored) {
            // no datapacks folder
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--datapack")) {
                try { dataPacks.add(PackSource.open(Path.of(args[i + 1]))); }
                catch (IOException e) { System.err.println("Cannot open data pack " + args[i + 1] + ": " + e.getMessage()); }
            }
        }
        RecipeBook recipes = RecipeBook.load(dataPacks);
        recipes.warnings.forEach(w -> System.err.println("[data] " + w));
        InventoryScreen inventoryScreen = new InventoryScreen(Input.placeableBlocks());
        CraftingMenu menu = null;   // open survival screen (2x2 inventory or 3x3 table)
        boolean screenOpen = false; // any screen, including the creative palette (menu == null)
        if (argv.contains("--inventory-demo")) { // scripted screenshot: a crafting grid in use
            inventory.add(Blocks.OAK_LOG, 8);
            inventory.add(Blocks.BIRCH_PLANKS, 3);
            inventory.add(Blocks.COBBLESTONE, 40);
            inventory.add(Blocks.GLASS, 6);
            if (!creative) {
                menu = new CraftingMenu(inventory, recipes, 2);
                menu.click(0, true, false);                 // half the logs on the cursor
                menu.click(menu.gridStart(), false, false); // into the grid: 4 planks offered
            }
            screenOpen = true;
        }
        if (argv.contains("--survival-demo")) { // scripted HUD check: some loot, some damage, some air
            inventory.add(Blocks.COBBLESTONE, 37);
            inventory.add(Blocks.OAK_LOG, 12);
            inventory.add(Blocks.DIRT, 64);
            inventory.add(Blocks.GLASS, 5);
            inventory.add(Blocks.OAK_SLAB, 1);
            survival.health = 13;
            survival.air = 130;
        }
        String pos = option(args, "--pos", null); // "x,y,z": start there, flying (scripted views)
        if (pos != null) {
            String[] xyz = pos.split(",");
            player.x = Double.parseDouble(xyz[0]);
            player.y = Double.parseDouble(xyz[1]);
            player.z = Double.parseDouble(xyz[2]);
            player.flying = true;
        }
        double ticks = Double.parseDouble(option(args, "--time", Long.toString(storage.dayTime)));
        boolean timeFrozen = screenshot != null; // scripted shots must be reproducible
        float[] sky = new float[3];
        // Benchmark: fly east at a fixed speed and height through fresh terrain, then report timings.
        double benchSeconds = Double.parseDouble(option(args, "--benchmark", "0"));
        FrameStats stats = benchSeconds > 0 ? new FrameStats() : null;
        if (stats != null) {
            player.flying = true;
            player.y = world.generator.height((int) player.x, (int) player.z) + 30;
            cam.yaw = (float) Math.toRadians(-90);
            cam.pitch = (float) Math.toRadians(-15);
            timeFrozen = true;
        }
        long benchStart = System.nanoTime();
        long lastAutosave = System.nanoTime();
        if (screenshot != null) cam.pitch = -0.35f;
        String view = option(args, "--view", null); // "yawDegrees,pitchDegrees", for scripted screenshots
        if (view != null) {
            String[] yp = view.split(",");
            cam.yaw = (float) Math.toRadians(Double.parseDouble(yp[0]));
            cam.pitch = (float) Math.toRadians(Double.parseDouble(yp[1]));
        }
        Input input = new Input(window);
        input.creative = creative;
        input.slot = playerData.selectedSlot;

        int[] fbW = new int[1], fbH = new int[1];
        long last = System.nanoTime(), fpsTimer = last;
        int frames = 0;
        double accumulator = 0, tickAccumulator = 0;
        FluidSimulator fluids = new FluidSimulator(world, renderer::blockChanged);
        float far = RENDER_RADIUS * 16f;
        Weather weather = new Weather(world, (x, y, z) -> {
            renderer.blockChanged(x, y, z);
            fluids.blockChanged(x, y, z);
        });
        int forced = argv.indexOf("--weather"); // scripted shots: rain, snow, sleet, hail or storm
        if (forced >= 0 && forced + 1 < args.length) weather.force(args[forced + 1]);
        weather.second(player.x, player.y, player.z, (float) Math.max(0, DayCycle.sunHeight(ticks)), false);
        WeatherRenderer weatherRenderer = new WeatherRenderer();
        Minimap minimap = new Minimap(world.generator);
        String mapMode = option(args, "--minimap", null); // scripted shots: relief, biomes, waters, clouds, weather
        if (mapMode != null) minimap.mode = Minimap.Mode.valueOf(mapMode.toUpperCase());
        double weatherClock = 0;
        // Start-up: the water of the first chunks settles at full speed, with a progress bar.
        boolean settling = true;
        long settleFrom = 0, rateUpdates = 0;
        double fluidRate = 0;
        int weatherTicks = 0;

        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            long frameNanos = now - last;
            double dt = Math.min(0.25, frameNanos / 1e9);
            last = now;

            glfwPollEvents();
            streamer.update((int) Math.floor(player.x) >> 4, (int) Math.floor(player.z) >> 4);
            world.light.drainTouched(k -> renderer.sectionChanged(k, false));
            if (now - lastAutosave > AUTOSAVE_NANOS) {
                streamer.autosave();
                lastAutosave = now;
            }
            if (stats == null && screenshot == null) {
                boolean escape = input.pressed(GLFW_KEY_ESCAPE), inventoryKey = input.pressed(GLFW_KEY_E);
                if (screenOpen && (escape || inventoryKey)) {
                    if (menu != null && menu.close() > 0) System.err.println("[inventory] full: items lost");
                    menu = null;
                    screenOpen = false;
                    input.capture(true);
                } else if (escape) {
                    paused = !paused;
                    input.capture(!paused);
                } else if (inventoryKey && !paused && !survival.dead) {
                    menu = creative ? null : new CraftingMenu(inventory, recipes, 2);
                    screenOpen = true;
                    input.capture(false);
                }
                if (screenOpen) {
                    double[] m = input.mouseInFramebuffer();
                    boolean left = input.clicked(GLFW_MOUSE_BUTTON_LEFT), right = input.clicked(GLFW_MOUSE_BUTTON_RIGHT);
                    int slot = (left || right) ? inventoryScreen.slotAt(hud, menu, m[0], m[1]) : -1;
                    if (slot >= 0) {
                        if (menu != null) menu.click(slot, right, input.shiftDown());
                        else if (inventoryScreen.isPalette(null, slot)) input.hotbar[input.slot] = inventoryScreen.paletteBlock(slot);
                        else if (slot < Inventory.HOTBAR) input.slot = slot;
                    }
                }
                if (input.pressed(GLFW_KEY_F3)) debug = !debug;
                if (input.pressed(GLFW_KEY_M)) minimap.cycle();
                if (!paused && input.pressed(GLFW_KEY_F4)) {
                    creative = !creative; // like vanilla's game mode switch
                    input.creative = creative;
                    if (!creative) player.flying = false;
                }
                if (survival.dead && input.pressed(GLFW_KEY_R)) {
                    survival.respawn();
                    player.x = storage.spawn[0] + 0.5;
                    player.y = storage.spawn[1];
                    player.z = storage.spawn[2] + 0.5;
                    player.vx = player.vy = player.vz = 0;
                }
                if (paused && input.pressed(GLFW_KEY_Q)) glfwSetWindowShouldClose(window, true);
            }
            if (paused || survival.dead) dt = 0; // the world stands still: no physics, no time, no edits
            if (stats == null && dt > 0 && !screenOpen) input.frame(cam, player, dt);
            else { input.breakRequested = false; input.placeRequested = false; input.breakHeld = false; }
            if (!creative) input.selected = inventory.id(input.slot);
            // Fixed physics step: frame-rate independent and stable collisions.
            // Game ticks (20 per second, like vanilla) drive the fluids.
            tickAccumulator += dt;
            while (tickAccumulator >= TICK) {
                fluids.tick();
                if (++weatherTicks == 20) { // the air moves once per game second
                    weatherTicks = 0;
                    weather.second(player.x, player.y, player.z, (float) Math.max(0, DayCycle.sunHeight(ticks)),
                        screenshot == null);
                }
                if (screenshot == null) survival.tick(player, world, creative); // scripted shots stay reproducible
                if (survival.lastDamage > 0) hurtFlashUntil = now + 300_000_000L;
                tickAccumulator -= TICK;
            }
            accumulator += dt;
            while (accumulator >= PHYSICS_STEP) {
                if (stats == null && !screenOpen) input.move(cam, player);
                else if (stats == null) { player.vx = 0; player.vz = 0; }
                else { player.vx = BENCH_SPEED; player.vz = 0; player.vy = 0; }
                player.step(world, PHYSICS_STEP);
                accumulator -= PHYSICS_STEP;
            }
            cam.pos.set(player.x, player.y + Player.EYE_HEIGHT, player.z);

            double cp = Math.cos(cam.pitch);
            Raycast.Hit hit = Raycast.cast(world, cam.pos.x, cam.pos.y, cam.pos.z,
                -Math.sin(cam.yaw) * cp, Math.sin(cam.pitch), -Math.cos(cam.yaw) * cp, REACH);
            // Survival mining: hold the button for the block's break time, then collect its drop.
            if (!creative && input.breakHeld && hit != null) {
                if (mining == null || mining[0] != hit.x() || mining[1] != hit.y() || mining[2] != hit.z()) {
                    mining = new int[] {hit.x(), hit.y(), hit.z()};
                    miningProgress = 0;
                }
                byte target = world.get(hit.x(), hit.y(), hit.z());
                miningProgress += dt / Blocks.breakSeconds(target);
                if (miningProgress >= 1 && world.set(hit.x(), hit.y(), hit.z(), Blocks.AIR)) {
                    inventory.add(Blocks.DROP[target & 0xFF], Blocks.DROP_COUNT[target & 0xFF]);
                    renderer.blockChanged(hit.x(), hit.y(), hit.z());
                    fluids.blockChanged(hit.x(), hit.y(), hit.z());
                    mining = null;
                    miningProgress = 0;
                }
            } else {
                mining = null;
                miningProgress = 0;
            }
            if (hit != null && ((creative && input.breakRequested) || (editDemo && streamer.settled()))) {
                if (world.get(hit.x(), hit.y(), hit.z()) != Blocks.BEDROCK && world.set(hit.x(), hit.y(), hit.z(), Blocks.AIR)) {
                    renderer.blockChanged(hit.x(), hit.y(), hit.z());
                    fluids.blockChanged(hit.x(), hit.y(), hit.z());
                }
            }
            if (hit != null && input.placeRequested && !creative && !input.shiftDown()
                && world.get(hit.x(), hit.y(), hit.z()) == Blocks.CRAFTING_TABLE) {
                menu = new CraftingMenu(inventory, recipes, 3); // like vanilla: sneak to place against it instead
                screenOpen = true;
                input.capture(false);
            } else if (hit != null && input.placeRequested && input.selected != Blocks.AIR) {
                double dirX = -Math.sin(cam.yaw) * cp, dirY = Math.sin(cam.pitch), dirZ = -Math.cos(cam.yaw) * cp;
                int[] placed = Placement.place(world, player, hit, hitHeight(hit, cam.pos.x, cam.pos.y, cam.pos.z, dirX, dirY, dirZ), input.selected);
                if (placed != null) {
                    if (!creative) inventory.takeOne(input.slot);
                    renderer.blockChanged(placed[0], placed[1], placed[2]);
                    fluids.blockChanged(placed[0], placed[1], placed[2]);
                }
            }
            if (editDemo && streamer.settled()) {
                // Self-test for incremental remeshing and light: a pillar crossing a section border,
                // and a plank roof casting a shadow with a glowstone lamp and glass underneath.
                int ox = (int) Math.floor(player.x), oy = (int) player.y, oz = (int) Math.floor(player.z);
                for (int y = 0; y < 20; y++) demoSet(world, renderer, ox - 3, oy + y, oz - 6, Blocks.STONE);
                for (int dx = 0; dx < 7; dx++)
                    for (int dz = 0; dz < 7; dz++) demoSet(world, renderer, ox + 1 + dx, oy + 4, oz - 9 + dz, Blocks.OAK_PLANKS);
                demoSet(world, renderer, ox + 4, oy + 3, oz - 6, Blocks.GLOWSTONE);
                demoSet(world, renderer, ox + 2, oy, oz - 4, Blocks.GLASS);
                demoSet(world, renderer, ox + 2, oy + 1, oz - 4, Blocks.GLASS);
                // Horizontal logs along x and z, and a slab staircase.
                for (int i = 0; i < 4; i++) demoSet(world, renderer, ox - 2 + i, oy, oz - 3, Blocks.OAK_LOG_X);
                for (int i = 0; i < 4; i++) demoSet(world, renderer, ox - 3, oy, oz - 3 + i, Blocks.OAK_LOG_Z);
                demoSet(world, renderer, ox + 6, oy, oz - 3, Blocks.OAK_SLAB);
                demoSet(world, renderer, ox + 7, oy, oz - 3, Blocks.OAK_SLAB_DOUBLE);
                demoSet(world, renderer, ox + 8, oy + 1, oz - 3, Blocks.OAK_SLAB);
                demoSet(world, renderer, ox + 6, oy + 2, oz - 2, Blocks.OAK_SLAB_TOP);
                editDemo = false;
            }

            world.light.drainTouched(k -> renderer.sectionChanged(k, true));

            for (int[] f; (f = springs.poll()) != null; )
                for (int i = 0; i < f.length; i += 3) fluids.blockChanged(f[i], f[i + 1], f[i + 2]);
            if (settling) {
                fluids.fastForward(8_000_000L);
                if (streamer.settled() && fluids.pending() == 0) settling = false;
            }

            if (!timeFrozen) ticks += dt * 20;
            weatherClock += dt;
            DayCycle.skyColor(ticks, sky);
            renderer.daylight = DayCycle.daylight(ticks);
            applyWeather(weather.here(), weatherRenderer.flash, sky, renderer);
            glClearColor(sky[0], sky[1], sky[2], 1);

            glfwGetFramebufferSize(window, fbW, fbH);
            if (stats != null) stats.beginGpu();
            glViewport(0, 0, fbW[0], fbH[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            float aspect = fbW[0] / (float) Math.max(1, fbH[0]);
            cam.viewProj(skyViewProj, aspect, far);
            skyRenderer.drawCelestial(skyViewProj, ticks);
            if (debug) gpuTimer.begin();
            renderer.render(cam, aspect, far, sky);
            weatherRenderer.draw(skyViewProj, weather, world, cam.pos.x, cam.pos.y, cam.pos.z, weatherClock,
                renderer.daylight, far, timeFrozen);
            if (hit != null) {
                byte target = world.get(hit.x(), hit.y(), hit.z());
                float lo = Blocks.isSlab(target) ? Blocks.BOX_MIN[target & 0xFF] / 16f : 0;
                float hi = Blocks.isSlab(target) ? Blocks.BOX_MAX[target & 0xFF] / 16f : 1;
                overlay.outline(renderer.viewProj(), (float) (hit.x() - cam.pos.x), (float) (hit.y() - cam.pos.y),
                    (float) (hit.z() - cam.pos.z), lo, hi);
            }
            overlay.crosshair(fbW[0], fbH[0]);
            if (debug) gpuTimer.end();
            hud.begin(fbW[0], fbH[0]);
            float hotbarTop = creative ? hud.hotbar(input.hotbar, null, input.slot)
                : hud.hotbar(java.util.Arrays.copyOf(inventory.ids, Inventory.HOTBAR),
                    java.util.Arrays.copyOf(inventory.counts, Inventory.HOTBAR), input.slot);
            if (!creative) {
                hotbarTop = hud.status(hotbarTop, hud.hotbarLeft(), hud.hotbarRight(), survival.health, survival.air, Survival.MAX_AIR);
                if (miningProgress > 0) hud.progress((float) miningProgress);
            }
            if (now < hurtFlashUntil) hud.rect(0, 0, hud.width(), hud.height(), 0.8f, 0, 0, 0.25f);
            if (input.selected != shownBlock) {
                shownBlock = input.selected;
                blockShownAt = now;
            }
            if (now - blockShownAt < 2_000_000_000L) {
                hud.textCentered(Blocks.NAMES[shownBlock], hud.width() / 2f, hotbarTop - hud.lineHeight() - 2 * hud.scale, 1, 1, 1);
            }
            if (debug) {
                debugScreen(hud, world, renderer, streamer, player, cam, hit, ticks, fps, frameMillis, gpuTimer.lastMillis,
                    fluids.pending(), fluidRate, settling, weather.here());
            }
            if (screenOpen) {
                double[] m = screenshot != null ? new double[] {hud.width() * 0.62, hud.height() * 0.42} : input.mouseInFramebuffer();
                inventoryScreen.draw(hud, menu, inventory, input.hotbar, input.slot, m[0], m[1]);
            }
            int basins = world.generator.landscape().hydrology().computing.get();
            if (basins > 0) { // river tiles being carved (the chunks there wait for them)
                hud.textCentered(String.format("Creusement des bassins versants : %d tuile%s de 1024 blocs en calcul...", basins,
                    basins > 1 ? "s" : ""), hud.width() / 2f, hud.height() * 0.3f - 3 * hud.lineHeight(), 0.85f, 0.95f, 1);
            }
            if (survival.dead) {
                hud.rect(0, 0, hud.width(), hud.height(), 0.5f, 0, 0, 0.45f);
                float cy = hud.height() / 2f;
                hud.textCentered("Vous êtes mort !", hud.width() / 2f, cy - hud.lineHeight(), 1, 1, 1);
                hud.textCentered("R : réapparaître", hud.width() / 2f, cy + hud.lineHeight(), 0.9f, 0.9f, 0.9f);
            } else if (settling && fluids.pending() > 0) {
                // Progress of the start-up water settling: updates done against what is still queued.
                long done = fluids.updates - settleFrom;
                float f = done / (float) Math.max(1, done + fluids.pending());
                float w = hud.width() * 0.4f, x = (hud.width() - w) / 2, y = hud.height() * 0.3f;
                hud.textCentered(String.format("Écoulement de l'eau : %d blocs en attente", fluids.pending()),
                    hud.width() / 2f, y - 1.5f * hud.lineHeight(), 1, 1, 1);
                hud.rect(x, y, w, 3 * hud.scale, 0, 0, 0, 0.6f);
                hud.rect(x, y, w * f, 3 * hud.scale, 0.3f, 0.55f, 1, 0.9f);
            } else if (paused) {
                hud.rect(0, 0, hud.width(), hud.height(), 0, 0, 0, 0.5f);
                float cy = hud.height() / 2f;
                hud.textCentered("Pause", hud.width() / 2f, cy - 2 * hud.lineHeight(), 1, 1, 1);
                hud.textCentered("Échap : reprendre", hud.width() / 2f, cy, 0.85f, 0.85f, 0.85f);
                hud.textCentered("Q : sauvegarder et quitter", hud.width() / 2f, cy + hud.lineHeight(), 0.85f, 0.85f, 0.85f);
            }
            hud.end(textures.id);
            minimap.update(player.x, player.z, weather, now);
            if (minimap.ready()) {
                minimap.draw(hud);
                hud.begin(fbW[0], fbH[0]);
                minimap.overlay(hud, player.x, player.z, cam.yaw);
                hud.end(textures.id);
            }
            if (stats != null) {
                stats.endGpu();
                if (frames > 0 || now - fpsTimer > 0) stats.cpuFrame(frameNanos);
                double elapsed = (System.nanoTime() - benchStart) / 1e9;
                if (elapsed >= benchSeconds) {
                    long[] buf = renderer.bufferBytes();
                    System.out.println(stats.report(elapsed) + String.format(
                        " | cull+sort %.3f ms | sections drawn %d/%d meshed | %d quads | blocks+light %.1f MiB (%d chunks) | GPU buffer %.1f MiB",
                        renderer.cullNanos / 1e6 / Math.max(1, renderer.cullFrames),
                        renderer.drawnSections, renderer.meshCount(), renderer.totalQuads,
                        world.storageBytes() / 1048576.0, world.loadedChunks().size(), buf[0] / 1048576.0));
                    glfwSetWindowShouldClose(window, true);
                }
            }
            if (fluidDemo && streamer.settled()) {
                // Self-test for fluids: a waterfall from a pillar, a lava pool meeting water.
                int ox = (int) Math.floor(player.x), oy = (int) player.y, oz = (int) Math.floor(player.z);
                for (int y = 0; y < 6; y++) demoSet(world, renderer, ox + 2, oy + y, oz - 7, Blocks.STONE);
                demoSet(world, renderer, ox + 2, oy + 6, oz - 7, Blocks.WATER);
                demoSet(world, renderer, ox - 4, oy, oz - 9, Blocks.LAVA);
                demoSet(world, renderer, ox - 4, oy, oz - 6, Blocks.WATER);
                fluids.blockChanged(ox + 2, oy + 6, oz - 7);
                fluids.blockChanged(ox - 4, oy, oz - 9);
                fluids.blockChanged(ox - 4, oy, oz - 6);
                fluidDemo = false;
            }
            if (screenshot != null && streamer.settled() && renderer.idle() && fluids.pending() == 0 && !fluidDemo
                && (minimap.mode == Minimap.Mode.OFF || minimap.ready())) {
                Screenshot.save(screenshot, fbW[0], fbH[0]);
                long[] buf = renderer.bufferBytes();
                System.out.printf("[stats] sections: %d drawn (%d in frustum, %d meshed) | quads: %d | GPU buffer %.1f/%.1f MiB%n",
                    renderer.drawnSections, renderer.frustumSections, renderer.meshCount(), renderer.totalQuads,
                    buf[0] / 1048576.0, buf[1] / 1048576.0);
                glfwSetWindowShouldClose(window, true);
            }
            glfwSwapBuffers(window);

            frames++;
            if (now - fpsTimer >= 1_000_000_000L) {
                fps = frames;
                fluidRate = (fluids.updates - rateUpdates) * 1e9 / (now - fpsTimer);
                rateUpdates = fluids.updates;
                frameMillis = (now - fpsTimer) / 1e6 / Math.max(1, frames);
                glfwSetWindowTitle(window, String.format("Mynecraft | %d FPS | %d/%d sections | %d quads | chargement %d | %s%s | %.1f %.1f %.1f",
                    frames, renderer.drawnSections, renderer.meshCount(), renderer.totalQuads, streamer.loadingCount(),
                    Blocks.NAMES[input.selected], player.flying ? " | vol" : "", player.x, player.y, player.z));
                frames = 0;
                fpsTimer = now;
            }
        }

        try {
            streamer.saveAll();
            playerData.pos = new double[] {player.x, player.y, player.z};
            playerData.health = survival.health;
            playerData.air = survival.air;
            playerData.creative = creative;
            playerData.selectedSlot = input.slot;
            storage.saveLevel(playerData, (long) ticks % DayCycle.DAY_TICKS);
            storage.close();
        } catch (IOException | InterruptedException e) {
            System.err.println("[storage] save failed: " + e.getMessage());
        }
        streamer.close();
        overlay.dispose();
        hud.dispose();
        gpuTimer.dispose();
        skyRenderer.dispose();
        weatherRenderer.dispose();
        minimap.dispose();
        renderer.dispose();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
