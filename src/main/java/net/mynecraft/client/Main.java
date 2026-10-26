package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.Colormap;
import net.mynecraft.resource.FontLoader;
import net.mynecraft.resource.PackSource;
import net.mynecraft.resource.ResourceManager;
import net.mynecraft.world.life.Creature;
import net.mynecraft.world.life.Life;
import net.mynecraft.world.weather.Atmosphere;
import net.mynecraft.world.weather.Weather;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.ExploredMap;
import net.mynecraft.client.sound.Synth;
import net.mynecraft.world.ChunkStreamer;
import net.mynecraft.world.storage.WorldStorage;
import net.mynecraft.world.FluidSimulator;
import net.mynecraft.world.crafting.CraftingMenu;
import net.mynecraft.world.crafting.RecipeIndex;
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

    private static void saveEcology(Life life, Path worldDir) {
        try {
            life.ecology.save(worldDir.resolve("ecology.bin"));
        } catch (IOException e) {
            System.err.println("[life] cannot save ecology.bin: " + e.getMessage());
        }
    }

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
        PauseMenu pauseMenu = new PauseMenu(worldDir, storage.seed);
        ExploredMap explored = new ExploredMap(worldDir);
        Waypoints waypoints = new Waypoints(worldDir);
        java.util.List<String> restart = null; // arguments to relaunch with (another world), set by the menu
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
                    explored.see(cx, cz);
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
        // Animals (M22): simulated around the player, drawn with the world and its shadows.
        Life life = new Life(world, world.seed);
        try { // the ecosystem's patches as they were left (else bootstrapped afresh)
            life.ecology.load(worldDir.resolve("ecology.bin"));
        } catch (IOException e) {
            System.err.println("[life] cannot read ecology.bin: " + e.getMessage());
        }
        Notebook notebook = new Notebook();
        notebook.open = argv.contains("--notebook");
        CreatureRenderer creatureRenderer = new CreatureRenderer();
        renderer.creatures = creatureRenderer;
        boolean showcase = argv.contains("--showcase");
        double lifeWait = Double.parseDouble(option(argv.toArray(new String[0]), "--life-seconds", showcase ? "2" : "0")), lifeClock = 0;
        Creature aimed = null;
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
        ItemNames itemNames = new ItemNames(resources);
        inventoryScreen.names = itemNames;
        RecipeViewer viewer = new RecipeViewer(new RecipeIndex(recipes), itemNames, Input.placeableBlocks());
        int[] drag = new int[64];      // slots crossed while dragging a held stack
        int dragCount = 0, dragButton = -1;
        long lastClickAt = 0;
        int lastClickSlot = -1;
        CraftingMenu menu = null;   // open survival screen (2x2 inventory or 3x3 table)
        boolean screenOpen = false; // any screen, including the creative palette (menu == null)
        if (argv.contains("--inventory-demo")) { // scripted screenshot: a crafting grid in use
            inventory.add(Blocks.OAK_LOG, 8);
            inventory.add(Blocks.BIRCH_PLANKS, 3);
            inventory.add(Blocks.COBBLESTONE, 40);
            inventory.add(Blocks.GLASS, 6);
            inventory.add(Blocks.OAK_PLANKS, 9);
            viewer.show(Blocks.CRAFTING_TABLE, false); // and the recipe viewer on the crafting table
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
        String pos = option(args, "--pos", null); // "x,y,z" (y "~": on the ground): start there, flying (scripted views)
        if (pos != null) {
            String[] xyz = pos.split(",");
            player.x = Double.parseDouble(xyz[0]);
            player.z = Double.parseDouble(xyz[2]);
            if (xyz[1].equals("~")) { // on the ground (or the water) there
                var col = world.generator.column((int) Math.floor(player.x), (int) Math.floor(player.z));
                player.y = Math.max(col.height(), col.water()) + 1.2;
            } else player.y = Double.parseDouble(xyz[1]);
            player.flying = true;
        }
        double ticks = Double.parseDouble(option(args, "--time", Long.toString(storage.dayTime)));
        String seasonName = option(args, "--season", null); // printemps, ete, automne, hiver: jump to that season
        if (seasonName != null) {
            int season = switch (seasonName.toLowerCase(java.util.Locale.ROOT)) {
                case "ete", "été", "summer" -> 1;
                case "automne", "autumn", "fall" -> 2;
                case "hiver", "winter" -> 3;
                default -> 0;
            };
            ticks = net.mynecraft.world.life.Ecosystem.ticksForSeason(season, ticks, DayCycle.DAY_TICKS);
        }
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
        Minimap minimap = new Minimap(world.generator, explored);
        WorldMap worldMap = new WorldMap(world.generator, explored, waypoints);
        SoundEngine sound = new SoundEngine();
        // Options shared by all worlds, next to them: for now the master volume.
        java.util.Properties options = new java.util.Properties();
        Path optionsFile = worldDir.toAbsolutePath().getParent().resolve("options.properties");
        try (var in = java.nio.file.Files.newBufferedReader(optionsFile)) {
            options.load(in);
        } catch (IOException ignored) { // first run: defaults
        }
        pauseMenu.volume = Math.max(0, Math.min(100, Integer.parseInt(options.getProperty("volume", "60").trim())));
        sound.setVolume(pauseMenu.volume / 100f);
        double stepDistance = 0, lastStepX = player.x, lastStepZ = player.z, waterScanAt = -1;
        SoundEngine.Ambience ambience = new SoundEngine.Ambience();
        double nextHitSound = 0;
        byte lastFluid = Blocks.FLUID_NONE;
        String mapMode = option(args, "--minimap", null); // scripted shots: relief, biomes, waters, clouds, weather
        if (mapMode != null) minimap.mode = Minimap.Mode.valueOf(mapMode.toUpperCase());
        if (argv.contains("--worldmap")) worldMap.open(player.x, player.z); // scripted shots
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
                explored.save();
                saveEcology(life, worldDir);
                lastAutosave = now;
            }
            if (stats == null && screenshot == null) {
                boolean escape = input.pressed(GLFW_KEY_ESCAPE), inventoryKey = input.pressed(GLFW_KEY_E);
                if (screenOpen && viewer.typing) { // typing a search: letters are text, Esc stops typing
                    viewer.type(input.takeTyped(), input.pressed(GLFW_KEY_BACKSPACE));
                    if (escape || input.pressed(GLFW_KEY_ENTER)) viewer.typing = false;
                    escape = inventoryKey = false;
                }
                if (worldMap.open && escape) {
                    worldMap.open = false;
                    input.capture(true);
                } else if (screenOpen && (escape || inventoryKey)) {
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
                    int hover = inventoryScreen.slotAt(hud, menu, m[0], m[1]);
                    viewer.scroll(hud, m[0], m[1], input.takeScroll());
                    if (!viewer.typing) input.takeTyped();
                    // Dragging a held stack over slots: released, it is spread over them (one each with
                    // the right button); a press and release on one slot stays a plain click.
                    if (dragButton >= 0) {
                        if (hover >= 0 && hover < menu.resultSlot() && dragCount < drag.length
                            && java.util.Arrays.stream(drag, 0, dragCount).noneMatch(x -> x == hover)) drag[dragCount++] = hover;
                        if (!input.held(dragButton)) {
                            if (dragCount > 1) menu.spread(java.util.Arrays.copyOf(drag, dragCount), dragButton == GLFW_MOUSE_BUTTON_RIGHT);
                            else if (dragCount == 1) menu.click(drag[0], dragButton == GLFW_MOUSE_BUTTON_RIGHT, false);
                            dragButton = -1;
                            dragCount = 0;
                        }
                    } else if ((left || right) && viewer.click(hud, m[0], m[1], right, input.shiftDown(), menu, input.hotbar, input.slot)) {
                        sound.click();
                    } else if ((left || right) && hover >= 0) {
                        sound.click();
                        if (menu != null) {
                            long t = System.nanoTime();
                            boolean dbl = left && hover == lastClickSlot && t - lastClickAt < 300_000_000L;
                            lastClickAt = t;
                            lastClickSlot = hover;
                            if (dbl && menu.cursorCount > 0) menu.collect(); // double click: gather the same item
                            else if (menu.cursorCount > 0 && !input.shiftDown() && hover < menu.resultSlot()) {
                                dragButton = left ? GLFW_MOUSE_BUTTON_LEFT : GLFW_MOUSE_BUTTON_RIGHT; // maybe a drag
                                drag[0] = hover;
                                dragCount = 1;
                            } else menu.click(hover, right, input.shiftDown());
                        } else if (inventoryScreen.isPalette(null, hover)) input.hotbar[input.slot] = inventoryScreen.paletteBlock(hover);
                        else if (hover < Inventory.HOTBAR) input.slot = hover;
                    }
                    if (!viewer.typing) {
                        // R / U over any item: its recipes / what it is used for (JEI keys).
                        byte under = hover >= 0 ? inventoryScreen.idAt(menu, hover, input.hotbar) : viewer.hovered(hud, m[0], m[1]);
                        if (under != Blocks.AIR && input.pressedChar('r')) viewer.show(under, false);
                        if (under != Blocks.AIR && input.pressedChar('u')) viewer.show(under, true);
                        if (menu != null && input.pressedChar('t')) menu.sort();
                        for (int k = 0; k < Inventory.HOTBAR; k++) // number key over a slot: swap with the hotbar
                            if (input.pressed(GLFW_KEY_1 + k) && menu != null && hover >= 0) menu.swapWithHotbar(hover, k);
                    }
                }
                if (input.pressed(GLFW_KEY_F3)) debug = !debug;
                if (worldMap.open) {
                    if (!worldMap.input(input, hud, player.x, player.z, (int) player.y)) input.capture(true);
                } else if (!paused && !screenOpen) {
                    if (input.pressedChar('m')) minimap.cycle();
                    if (input.pressedChar('j')) { // world map
                        worldMap.open(player.x, player.z);
                        input.capture(false);
                    }
                    if (input.pressedChar('n')) notebook.open = !notebook.open; // naturalist's notebook
                    if (input.pressedChar('b')) // waypoint where the player stands
                        waypoints.add((int) Math.floor(player.x), (int) Math.floor(player.y), (int) Math.floor(player.z));
                }
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
                if (paused) {
                    PauseMenu.Action action = PauseMenu.Action.NONE;
                    if (pauseMenu.typing) {
                        for (char ch : input.takeTyped().toCharArray())
                            if (ch >= ' ' && pauseMenu.seedText.length() < 32) pauseMenu.seedText.append(ch);
                        if (input.pressed(GLFW_KEY_BACKSPACE) && pauseMenu.seedText.length() > 0)
                            pauseMenu.seedText.setLength(pauseMenu.seedText.length() - 1);
                        if (input.pressed(GLFW_KEY_ENTER) && pauseMenu.seedText.length() > 0) {
                            pauseMenu.typing = false;
                            action = PauseMenu.Action.NEW_TYPED_SEED;
                        }
                    } else {
                        input.takeTyped();
                    }
                    boolean left = input.clicked(GLFW_MOUSE_BUTTON_LEFT), right = input.clicked(GLFW_MOUSE_BUTTON_RIGHT);
                    if (left || right) {
                        double[] m = input.mouseInFramebuffer();
                        action = pauseMenu.click(hud, m[0], m[1], right);
                        sound.click();
                    }
                    switch (action) {
                        case RESUME -> {
                            paused = false;
                            input.capture(true);
                        }
                        case RESPAWN -> {
                            player.x = storage.spawn[0] + 0.5;
                            player.y = storage.spawn[1];
                            player.z = storage.spawn[2] + 0.5;
                            player.vx = player.vy = player.vz = 0;
                            paused = false;
                            input.capture(true);
                        }
                        case VOLUME_UP, VOLUME_DOWN -> {
                            pauseMenu.volume = Math.max(0, Math.min(100, pauseMenu.volume + (action == PauseMenu.Action.VOLUME_UP ? 10 : -10)));
                            sound.setVolume(pauseMenu.volume / 100f);
                            options.setProperty("volume", Integer.toString(pauseMenu.volume));
                            try (var out = java.nio.file.Files.newBufferedWriter(optionsFile)) {
                                options.store(out, "Mynecraft");
                            } catch (IOException e) {
                                System.err.println("[options] cannot save: " + e.getMessage());
                            }
                        }
                        case RELOAD -> restart = relaunchArgs(args, worldDir, null);
                        case NEW_RANDOM -> restart = relaunchArgs(args, pauseMenu.newWorldDir(), new java.util.Random().nextLong());
                        case NEW_SAME_SEED -> restart = relaunchArgs(args, pauseMenu.newWorldDir(), storage.seed);
                        case NEW_TYPED_SEED -> restart = relaunchArgs(args, pauseMenu.newWorldDir(), pauseMenu.typedSeed());
                        case OPEN_WORLD -> restart = relaunchArgs(args, pauseMenu.chosenWorld(), null);
                        case QUIT -> glfwSetWindowShouldClose(window, true);
                        default -> { }
                    }
                    if (restart != null) glfwSetWindowShouldClose(window, true);
                } else {
                    input.takeTyped();
                }
            }
            if (paused || survival.dead) dt = 0; // the world stands still: no physics, no time, no edits
            if (stats == null && dt > 0 && !screenOpen && !worldMap.open) input.frame(cam, player, dt);
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
                if (stats == null && !screenOpen && !worldMap.open) input.move(cam, player);
                else if (stats == null) { player.vx = 0; player.vz = 0; }
                else { player.vx = BENCH_SPEED; player.vz = 0; player.vy = 0; }
                player.step(world, PHYSICS_STEP);
                accumulator -= PHYSICS_STEP;
            }
            cam.pos.set(player.x, player.y + Player.EYE_HEIGHT, player.z);
            // Footsteps: one every 1.7 blocks walked on the ground (a splash in water), by material.
            double walked = Math.hypot(player.x - lastStepX, player.z - lastStepZ);
            lastStepX = player.x;
            lastStepZ = player.z;
            if ((player.onGround || player.fluid != Blocks.FLUID_NONE) && !player.flying && walked < 2) {
                stepDistance += walked;
                boolean wading = player.fluid == Blocks.FLUID_WATER;
                if (stepDistance > (wading ? 2.2 : 1.7)) {
                    stepDistance = 0;
                    byte under = world.get((int) Math.floor(player.x), (int) Math.floor(player.y - 0.2), (int) Math.floor(player.z));
                    sound.step(wading ? Synth.Material.WATER : material(under), player.x, player.y, player.z);
                }
            }

            if (!paused && !survival.dead) {
                life.eco.update(ticks, DayCycle.daylight(ticks), DayCycle.DAY_TICKS);
                weather.atmosphere.seasonOffset = (float) net.mynecraft.world.life.Ecosystem.temperatureOffset(life.eco.season().phase());
                renderer.seasonChanged(life.eco.season().phase());
                if (showcase && streamer.settled()) {
                    life.showcase(player.x, player.y, player.z, cam.yaw);
                    showcase = false;
                }
                life.tick(dt, player.x, player.y, player.z, input.shiftDown());
                if (streamer.settled()) lifeClock += dt;
                for (int[] e; (e = life.edits.poll()) != null; ) renderer.blockChanged(e[0], e[1], e[2]); // grass grown, eaten
            }
            double cp = Math.cos(cam.pitch);
            Raycast.Hit hit = Raycast.cast(world, cam.pos.x, cam.pos.y, cam.pos.z,
                -Math.sin(cam.yaw) * cp, Math.sin(cam.pitch), -Math.cos(cam.yaw) * cp, REACH);
            // An animal in front of the block aimed at is struck instead of the block.
            aimed = life.raycast(cam.pos.x, cam.pos.y, cam.pos.z, -Math.sin(cam.yaw) * cp, Math.sin(cam.pitch), -Math.cos(cam.yaw) * cp,
                hit == null ? REACH : Math.max(0, Math.sqrt(Math.pow(hit.x() + 0.5 - cam.pos.x, 2) + Math.pow(hit.y() + 0.5 - cam.pos.y, 2)
                    + Math.pow(hit.z() + 0.5 - cam.pos.z, 2)) - 0.5));
            if (aimed != null) {
                if (input.breakRequested && !screenOpen && !paused) {
                    life.strike(aimed, creative ? 100 : 4, player.x, player.z);
                    sound.hit(Synth.Material.WOOD, aimed.x, aimed.y + aimed.height() / 2, aimed.z);
                }
                hit = null;
            }
            // Survival mining: hold the button for the block's break time, then collect its drop.
            if (!creative && input.breakHeld && hit != null) {
                if (mining == null || mining[0] != hit.x() || mining[1] != hit.y() || mining[2] != hit.z()) {
                    mining = new int[] {hit.x(), hit.y(), hit.z()};
                    miningProgress = 0;
                }
                byte target = world.get(hit.x(), hit.y(), hit.z());
                miningProgress += dt / Blocks.breakSeconds(target);
                if (weatherClock >= nextHitSound && dt > 0) { // the tool striking, four times a second
                    sound.hit(material(target), hit.x() + 0.5, hit.y() + 0.5, hit.z() + 0.5);
                    nextHitSound = weatherClock + 0.25;
                }
                if (miningProgress >= 1 && world.set(hit.x(), hit.y(), hit.z(), Blocks.AIR)) {
                    sound.blockBroken(material(target), hit.x() + 0.5, hit.y() + 0.5, hit.z() + 0.5);
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
                byte broken = world.get(hit.x(), hit.y(), hit.z());
                if (broken != Blocks.BEDROCK && world.set(hit.x(), hit.y(), hit.z(), Blocks.AIR)) {
                    sound.blockBroken(material(broken), hit.x() + 0.5, hit.y() + 0.5, hit.z() + 0.5);
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
                    sound.blockPlaced(material(world.get(placed[0], placed[1], placed[2])), placed[0] + 0.5, placed[1] + 0.5, placed[2] + 0.5);
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
                for (int i = 0; i < f.length; i += 3) fluids.settle(f[i], f[i + 1], f[i + 2]);
            fluids.settleStep(3_000_000L); // flows of newly loaded land, pre-computed in the background budget
            if (settling) {
                fluids.fastForward(8_000_000L);
                if (streamer.settled() && fluids.pending() == 0) settling = false;
            }

            if (!timeFrozen) ticks += dt * 20;
            weatherClock += dt;
            renderer.time = weatherClock;
            DayCycle.skyColor(ticks, sky);
            renderer.daylight = DayCycle.daylight(ticks);
            {   // The sun for shadows and water: same path as the sun drawn in the sky, slightly tilted.
                double a = (ticks % DayCycle.DAY_TICKS) / DayCycle.DAY_TICKS * 2 * Math.PI;
                renderer.sunDir.set(Math.cos(a), Math.sin(a), 0.18).normalize();
                double h = Math.sin(a);
                Atmosphere.Sample w = weather.here();
                float overcast = w == null ? 0 : Math.min(1, Math.max(0, w.cloud() - 0.2f) * 1.2f);
                renderer.shadowStrength = (float) Math.max(0, Math.min(1, (h - 0.03) / 0.15)) * (1 - 0.85f * overcast);
            }
            applyWeather(weather.here(), weatherRenderer.flash, sky, renderer);
            // Overhead, the sky is deeper than at the horizon (the fog colour): water mirrors both.
            renderer.skyColor[0] = sky[0] * 0.7f;
            renderer.skyColor[1] = sky[1] * 0.82f;
            renderer.skyColor[2] = Math.min(1, sky[2] * 1.02f);
            glClearColor(sky[0], sky[1], sky[2], 1);

            glfwGetFramebufferSize(window, fbW, fbH);
            if (stats != null) stats.beginGpu();
            textures.animate(org.lwjgl.glfw.GLFW.glfwGetTime() * 20); // animated textures run on real time
            renderer.scene.bind(fbW[0], fbH[0]); // the world is drawn off-screen (water samples it), then shown
            glViewport(0, 0, fbW[0], fbH[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            float aspect = fbW[0] / (float) Math.max(1, fbH[0]);
            cam.viewProj(skyViewProj, aspect, far);
            skyRenderer.drawCelestial(skyViewProj, ticks);
            if (debug) gpuTimer.begin();
            creatureRenderer.prepare(life, world, cam, skyViewProj, renderer.shadows && renderer.shadowStrength > 0);
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
            renderer.scene.present();
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
            if (notebook.open && !screenOpen && !worldMap.open) notebook.draw(hud, life, player.x, player.z);
            if (aimed != null && !screenOpen) { // the name of the animal under the crosshair
                hud.textCentered(aimed.species.displayName + (aimed.adult() ? "" : " (jeune)"), hud.width() / 2f,
                    hud.height() / 2f + 2 * hud.lineHeight(), 1, 1, 0.85f);
            }
            if (now - blockShownAt < 2_000_000_000L) {
                hud.textCentered(itemNames.name(shownBlock), hud.width() / 2f, hotbarTop - hud.lineHeight() - 2 * hud.scale, 1, 1, 1);
            }
            if (debug) {
                debugScreen(hud, world, renderer, streamer, player, cam, hit, ticks, fps, frameMillis, gpuTimer.lastMillis,
                    fluids.pending(), fluidRate, settling, weather.here());
            }
            if (screenOpen) {
                double[] m = screenshot != null ? new double[] {hud.width() * 0.62, hud.height() * 0.42} : input.mouseInFramebuffer();
                inventoryScreen.draw(hud, menu, inventory, input.hotbar, input.slot, m[0], m[1]);
                viewer.draw(hud, menu, menu != null ? inventory : null, m[0], m[1]);
                String help = menu != null
                    ? "T : trier | 1-9 : vers la barre | double-clic : regrouper | glisser : répartir | R / U : recettes / utilisations"
                    : "clic : prendre un bloc | R / U : recettes / utilisations";
                hud.rect(0, hud.height() - hud.lineHeight() - 4 * hud.scale, hud.width(), hud.lineHeight() + 4 * hud.scale, 0, 0, 0, 0.55f);
                hud.textCentered(help, hud.width() / 2f, hud.height() - hud.lineHeight() - 2 * hud.scale, 0.85f, 0.85f, 0.85f);
            }
            if (!worldMap.open) drawWaypoints(hud, renderer, cam, waypoints);
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
                double[] m = screenshot != null ? new double[] {hud.width() / 2.0, hud.height() / 2.0} : input.mouseInFramebuffer();
                pauseMenu.draw(hud, m[0], m[1]);
            }
            hud.end(textures.id);
            minimap.update(player.x, player.z, weather, now);
            if (minimap.ready() && !screenOpen) {
                minimap.draw(hud);
                hud.begin(fbW[0], fbH[0]);
                minimap.overlay(hud, player.x, player.z, cam.yaw, waypoints);
                hud.end(textures.id);
            }
            if (worldMap.open) {
                worldMap.update(hud, weather, now);
                hud.begin(fbW[0], fbH[0]);
                hud.rect(0, 0, hud.width(), hud.height(), 0.06f, 0.07f, 0.09f, 1);
                hud.end(textures.id);
                worldMap.draw(hud);
                hud.begin(fbW[0], fbH[0]);
                worldMap.overlay(hud, input, player.x, player.z, cam.yaw);
                hud.end(textures.id);
            }
            // Sound: thunder after each flash; the ambience from the place, the hour and the weather
            // (the surroundings are looked at twice a second).
            double[] strike = weatherRenderer.takeStrike();
            if (strike != null) sound.lightning(strike[0], strike[1], strike[2], cam.pos.x, cam.pos.y, cam.pos.z, weatherClock);
            if (player.fluid == Blocks.FLUID_WATER && lastFluid == Blocks.FLUID_NONE && player.vy < -3)
                sound.splash(player.x, player.y + 0.5, player.z); // jumped or fell in
            lastFluid = player.fluid;
            if (sound.enabled()) {
                Atmosphere.Sample here = weather.here();
                if (weatherClock >= waterScanAt) {
                    surroundings(world, cam.pos.x, cam.pos.y, cam.pos.z, ambience);
                    waterScanAt = weatherClock + 0.5;
                }
                boolean wet = here != null && here.type() != Atmosphere.Precipitation.NONE && !paused;
                ambience.rain = wet ? Math.min(1, here.precipitation() / 0.03f) : 0;
                ambience.hail = wet && here.type() == Atmosphere.Precipitation.HAIL;
                ambience.snow = wet && here.type() == Atmosphere.Precipitation.SNOW;
                ambience.sheltered = sheltered(world, cam.pos.x, cam.pos.y, cam.pos.z);
                ambience.wind = here == null || paused ? 0 : (float) (Math.hypot(here.windX(), here.windZ()) * (1 + Math.max(0, cam.pos.y - 80) / 80));
                ambience.daylight = (DayCycle.daylight(ticks) - 0.15f) / 0.85f;
                sound.update(cam.pos.x, cam.pos.y, cam.pos.z, cam.yaw, cam.pitch, ambience, weatherClock);
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
            if (screenshot != null && streamer.settled() && renderer.idle() && fluids.pending() == 0 && !fluidDemo && lifeClock >= lifeWait && renderer.biomesSettled()
                && (!worldMap.open || worldMap.ready())
                && (minimap.mode == Minimap.Mode.OFF || minimap.ready())) {
                Screenshot.save(screenshot, fbW[0], fbH[0]);
                long[] buf = renderer.bufferBytes();
                System.out.printf("[stats] sections: %d drawn (%d in frustum, %d meshed) | quads: %d | GPU buffer %.1f/%.1f MiB%n",
                    renderer.drawnSections, renderer.frustumSections, renderer.meshCount(), renderer.totalQuads,
                    buf[0] / 1048576.0, buf[1] / 1048576.0);
                System.out.printf("[life] %d creatures (%d drawn) | spawned %d, born %d, eaten %d%n", life.creatures.size(),
                    creatureRenderer.drawn, life.spawned, life.born, life.eaten);
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
            storage.saveLevel(playerData, (long) ticks); // the whole calendar: the seasons go on from here
            storage.close();
            explored.save();
            saveEcology(life, worldDir);
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
        worldMap.dispose();
        sound.dispose();
        creatureRenderer.dispose();
        renderer.dispose();
        glfwDestroyWindow(window);
        glfwTerminate();
        if (restart != null) relaunch(restart);
    }

    /** What a block sounds like (steps, breaking, placing). */
    private static Synth.Material material(byte b) {
        if (b == Blocks.GRASS) return Synth.Material.GRASS;
        if (b == Blocks.DIRT) return Synth.Material.DIRT;
        if (b == Blocks.SAND) return Synth.Material.SAND;
        if (b == Blocks.GRAVEL) return Synth.Material.GRAVEL;
        if (b == Blocks.SNOW_BLOCK) return Synth.Material.SNOW;
        if (b == Blocks.GLASS || b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.GLOWSTONE) return Synth.Material.GLASS;
        if (b == Blocks.OAK_LEAVES || Blocks.SHAPE[b & 0xFF] == Blocks.SHAPE_CROSS) return Synth.Material.LEAVES;
        if (b == Blocks.OAK_PLANKS || b == Blocks.OAK_LOG || b == Blocks.OAK_LOG_X || b == Blocks.OAK_LOG_Z
            || b == Blocks.CRAFTING_TABLE || Blocks.isSlab(b)) return Synth.Material.WOOD;
        if (Blocks.isFluid(b)) return Synth.Material.WATER;
        return Synth.Material.STONE;
    }

    /** True if something solid stands over this point (rain is heard muffled). */
    private static boolean sheltered(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int yy = (int) Math.floor(y) + 1; yy < Math.min(Chunk.MAX_Y, y + 32); yy++) if (Blocks.isSolid(world.get(bx, yy, bz))) return true;
        return false;
    }

    /**
     * Looks around the listener for the sound ambience: up to 3 spots of running water (river
     * columns by their flow: a brook or a river; moving water blocks: a fall), nearest first and
     * at least 12 blocks apart; the nearest shore; how wooded and how grassy it is; whether we are
     * in a cave (no sky light and rock overhead).
     */
    private static void surroundings(World world, double x, double y, double z, SoundEngine.Ambience a) {
        java.util.List<double[]> found = new java.util.ArrayList<>();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        var hydrology = world.generator.landscape().hydrology();
        int trees = 0, grass = 0, columns = 0;
        double coastD = Double.MAX_VALUE;
        a.coast = null;
        for (int dz = -44; dz <= 44; dz += 4)
            for (int dx = -44; dx <= 44; dx += 4) {
                var c = world.generator.column(bx + dx, bz + dz);
                columns++;
                byte biome = c.biome();
                if (biome == Landscape.FOREST || biome == Landscape.BIRCH_FOREST || biome == Landscape.DARK_FOREST
                    || biome == Landscape.TAIGA || biome == Landscape.SNOWY_TAIGA) trees++;
                if (biome == Landscape.PLAINS || biome == Landscape.DRY_PLAINS || biome == Landscape.FOREST || biome == Landscape.BIRCH_FOREST) grass++;
                if (c.river() > 0) {
                    float flow = hydrology.flow(bx + dx, bz + dz);
                    found.add(new double[] {bx + dx + 0.5, c.water() + 0.5, bz + dz + 0.5,
                        Math.min(1, 0.35 + Math.log10(flow / 400 + 1) * 0.35), flow < 2500 ? 0 : 1});
                }
                if ((biome == Landscape.OCEAN || biome == Landscape.FROZEN_OCEAN) && c.height() > net.mynecraft.world.TerrainGenerator.SEA_LEVEL - 4) {
                    double d = dx * dx + dz * dz; // shallow sea near the shore: where waves break
                    if (d < coastD) {
                        coastD = d;
                        a.coast = new double[] {bx + dx + 0.5, net.mynecraft.world.TerrainGenerator.SEA_LEVEL + 0.5, bz + dz + 0.5};
                    }
                }
                if (Math.abs(dx) <= 24 && Math.abs(dz) <= 24)
                    for (int dy = -12; dy <= 12; dy += 3) { // falls and rapids: moving water blocks
                        byte b = world.get(bx + dx, by + dy, bz + dz);
                        if (Blocks.FLUID[b & 0xFF] == Blocks.FLUID_WATER && Blocks.LEVEL[b & 0xFF] != 0)
                            found.add(new double[] {bx + dx + 0.5, by + dy + 0.5, bz + dz + 0.5, Blocks.LEVEL[b & 0xFF] == 8 ? 1 : 0.6,
                                Blocks.LEVEL[b & 0xFF] == 8 ? 2 : 0});
                    }
            }
        a.forest = Math.min(1, trees * 2f / columns);
        a.grass = Math.min(1, grass * 1.5f / columns);
        found.sort(java.util.Comparator.comparingDouble(p -> (p[0] - x) * (p[0] - x) + (p[1] - y) * (p[1] - y) + (p[2] - z) * (p[2] - z)));
        java.util.List<double[]> spots = new java.util.ArrayList<>();
        for (double[] p : found) {
            boolean far = true;
            for (double[] q : spots) if (Math.abs(p[0] - q[0]) + Math.abs(p[2] - q[2]) < 12) far = false;
            if (far) spots.add(p);
            if (spots.size() == 3) break;
        }
        a.water = spots.toArray(new double[0][]);
        int sky = world.getLight(bx, by, bz) >> 4;
        a.underground = sheltered(world, x, y, z) ? Math.max(0, Math.min(1, (10 - sky) / 8f)) : 0;
    }

    /** Waypoints in the world: a marker and "name (distance)" over each one in front of the camera. */
    private static void drawWaypoints(Hud hud, WorldRenderer renderer, Camera cam, Waypoints waypoints) {
        org.joml.Matrix4f vp = renderer.viewProj();
        org.joml.Vector4f v = new org.joml.Vector4f();
        float s = hud.scale;
        for (Waypoints.Waypoint w : waypoints.list) {
            double dx = w.x + 0.5 - cam.pos.x, dy = w.y + 1.5 - cam.pos.y, dz = w.z + 0.5 - cam.pos.z;
            v.set((float) dx, (float) dy, (float) dz, 1).mul(vp);
            if (v.w <= 0.1f) continue; // behind the camera
            float sx = (v.x / v.w * 0.5f + 0.5f) * hud.width(), sy = (0.5f - v.y / v.w * 0.5f) * hud.height();
            if (sx < 0 || sy < 0 || sx > hud.width() || sy > hud.height()) continue;
            int c = w.color;
            float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
            hud.rect(sx - 2 * s, sy - 2 * s, 4 * s, 4 * s, 0, 0, 0, 0.7f);
            hud.rect(sx - 1.5f * s, sy - 1.5f * s, 3 * s, 3 * s, r, g, b, 1);
            String label = String.format("%s (%d m)", w.name, Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
            hud.rect(sx - hud.textWidth(label) / 2 - s, sy - 3 * s - hud.lineHeight(), hud.textWidth(label) + 2 * s, hud.lineHeight(), 0, 0, 0, 0.45f);
            hud.textCentered(label, sx, sy - 3 * s - hud.lineHeight(), r, g, b);
        }
    }

    /** Options naming the world or the start position: replaced or dropped when relaunching. */
    private static final java.util.Set<String> WORLD_OPTIONS = java.util.Set.of("--world", "--seed", "--pos", "--view",
        "--time", "--screenshot", "--weather", "--minimap", "--benchmark");

    /** The current arguments with another world (and a seed, for a new one). */
    private static java.util.List<String> relaunchArgs(String[] args, Path world, Long seed) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (WORLD_OPTIONS.contains(args[i])) { i++; continue; }
            if (args[i].equals("--paused")) continue;
            out.add(args[i]);
        }
        out.add("--world");
        out.add(world.toString());
        if (seed != null) {
            out.add("--seed");
            out.add(Long.toString(seed));
        }
        return out;
    }

    /** Starts the game again in a new process (same JVM options and class path), with these arguments. */
    private static void relaunch(java.util.List<String> args) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Main.class.getName());
        cmd.addAll(args);
        System.out.println("[menu] restarting: " + String.join(" ", args));
        try {
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (IOException e) {
            System.err.println("[menu] cannot restart: " + e.getMessage());
        }
    }
}
