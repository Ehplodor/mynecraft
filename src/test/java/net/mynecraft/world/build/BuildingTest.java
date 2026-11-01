package net.mynecraft.world.build;

import net.mynecraft.resource.PackSource;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Doors;
import net.mynecraft.world.Placement;
import net.mynecraft.world.Player;
import net.mynecraft.world.Raycast;
import net.mynecraft.world.Shapes;
import net.mynecraft.world.World;
import net.mynecraft.world.actor.Actor;
import net.mynecraft.world.actor.Flows;
import net.mynecraft.world.actor.Gestures;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.people.Hamlet;
import net.mynecraft.world.people.PathFinder;
import net.mynecraft.world.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Plans, sites, doors and furniture: what the hamlet is built of, and how it meets the ground. */
class BuildingTest {
    /** Empty chunks from -3 to 4 each way, ground of dirt under grass whose top is {@code height(x, z)}. */
    private static World land(IntBinaryOperator height) {
        World w = new World(0);
        for (int cx = -3; cx <= 4; cx++) for (int cz = -3; cz <= 4; cz++) w.getOrGenerateEmpty(cx, cz);
        for (int x = -48; x < 80; x++)
            for (int z = -48; z < 80; z++) {
                int top = height.applyAsInt(x, z);
                var c = w.chunk(x >> 4, z >> 4); // straight into the chunk: no light to work out in a test
                for (int y = 40; y < top; y++) c.set(x & 15, y, z & 15, y < top - 3 ? Blocks.STONE : Blocks.DIRT);
                c.set(x & 15, top, z & 15, Blocks.GRASS);
            }
        return w;
    }

    private static IntBinaryOperator ground(World w) {
        return (x, z) -> {
            for (int y = 120; y > 0; y--) {
                short b = w.get(x, y, z);
                if (Blocks.isSolid(b) && !Blocks.NAMES[b].endsWith("_leaves") && !Blocks.NAMES[b].endsWith("_log")) return y;
            }
            return 0;
        };
    }

    @Test
    void everyPlanHasWholeDoorsAYardAndRisesToTheEnd() {
        for (Blueprint p : Blueprint.builtIn().values()) {
            assertNotNull(p.marks.get("porte"), p.name + ": the yard before the door");
            for (int y = 0; y < p.h; y++)
                for (int z = 0; z < p.d; z++)
                    for (int x = 0; x < p.w; x++) {
                        short b = p.at(x, y, z);
                        if (!Blocks.isDoor(b) || b == Blocks.door(false, false, false) && p.name.equals(Blueprint.COOP) && y == p.sunk)
                            continue; // the hen-run's gate is a low door alone
                        short other = Blocks.UPPER[b] ? p.at(x, y - 1, z) : p.at(x, y + 1, z);
                        assertTrue(Blocks.isDoor(other) && Blocks.UPPER[other] != Blocks.UPPER[b], p.name + ": a door of two halves");
                    }
            assertFalse(p.materials().containsKey(Blocks.AIR));
            assertFalse(p.materials().containsKey(Blocks.OAK_DOOR_TOP), "the upper half comes with the door");
            World w = land((x, z) -> 60);
            int[] a = Site.anchor(p, 0, 60, 0);
            int n = 0;
            for (int[] s; (s = p.next(w, a[0], a[1], a[2])) != null && n < 5000; n++) w.set(s[0], s[1], s[2], (short) s[3]);
            assertNull(p.next(w, a[0], a[1], a[2]), p.name + " is finished");
            assertEquals(0, p.steps(w, a[0], a[1], a[2]));
            // Opened doors and a lantern put out are still the plan.
            for (int y = 0; y < p.h; y++)
                for (int z = 0; z < p.d; z++)
                    for (int x = 0; x < p.w; x++) {
                        short b = w.get(a[0] + x, a[1] + y, a[2] + z);
                        if (Blocks.isDoor(b)) w.set(a[0] + x, a[1] + y, a[2] + z, Blocks.withOpen(b, true));
                        if (b == Blocks.LANTERN) w.set(a[0] + x, a[1] + y, a[2] + z, Blocks.LANTERN_OFF);
                    }
            assertNull(p.next(w, a[0], a[1], a[2]));
            Blueprint back = Blueprint.fromJson(p.toJson());
            assertArrayEquals(p.cells, back.cells);
            assertEquals(p.sunk, back.sunk);
            assertArrayEquals(p.marks.get("porte"), back.marks.get("porte"));
        }
    }

    @Test
    void aHouseOnASlopeSitsOnItsPlinthWithFoundationsNotBuried() {
        World w = land((x, z) -> 60 + Math.floorDiv(x, 3)); // a slope rising to the east, a block every three
        Blueprint p = Blueprint.house();
        IntBinaryOperator g = ground(w);
        int[] a = Site.choose(w, p, 0, 0, 0, 0, List.of(), g);
        int lv = Site.level(w, p, 0, 0, g);
        assertEquals(lv + 1, a[1], "the plinth is laid on the median ground, one block proud");
        Site.prepare(w, p, a, w::set, null);
        for (int[] s; (s = p.next(w, a[0], a[1], a[2])) != null; ) w.set(s[0], s[1], s[2], (short) s[3]);
        for (int z = 0; z < p.d; z++)
            for (int x = 0; x < p.w; x++) {
                if (!Blocks.isSolid(p.at(x, 0, z)) || p.at(x, 0, z) == Blocks.OAK_FENCE) continue;
                int wx = a[0] + x, wz = a[2] + z;
                for (int y = a[1] - 1; y > lv - 12; y--) {
                    short b = w.get(wx, y, wz);
                    assertTrue(Blocks.isSolid(b), "no hollow under the plinth at " + wx + "," + y + "," + wz);
                    if (b == Blocks.STONE || b == Blocks.DIRT) break;
                }
            }
        // The ground round the walls is no higher than the plinth: the walls stand clear.
        for (int x = -1; x <= 9; x++)
            for (int z : new int[] {-1, 7})
                assertFalse(Blocks.isSolid(w.get(a[0] + 1 + x, a[1] + 1, a[2] + 1 + z)) && Site.natural(w.get(a[0] + 1 + x, a[1] + 1, a[2] + 1 + z)),
                    "earth against the wall at " + x + "," + z);
        // Stone foundations show on the low side.
        assertEquals(Blocks.COBBLESTONE, w.get(a[0] + 1, a[1] - 1, a[2] + 1));
    }

    @Test
    void doorsOpenCloseAndBreakAsOneAndLetPeopleThrough() {
        World w = land((x, z) -> 60);
        Player pl = new Player();
        pl.x = 0.5; pl.y = 61; pl.z = 0.5;
        int[] at = Placement.place(w, pl, new Raycast.Hit(0, 60, 3, 0, 1, 0), 1, Blocks.OAK_DOOR);
        assertArrayEquals(new int[] {0, 61, 3}, at);
        assertEquals(Blocks.door(false, false, false), w.get(0, 61, 3), "across the line of sight");
        assertEquals(Blocks.door(true, false, false), w.get(0, 62, 3));
        Actor a = new Actor("p", new Inventory());
        assertEquals(Gestures.Use.DOOR, Gestures.use(a, w, 0, 0, 62, 3, false, false, null, null, new SplittableRandom(1)));
        assertEquals(Blocks.door(false, false, true), w.get(0, 61, 3), "the lower half opens with the upper");
        assertFalse(Blocks.isSolid(w.get(0, 62, 3)));
        Doors.toggle(w, 0, 61, 3);
        assertTrue(Blocks.isClosedDoor(w.get(0, 62, 3)));
        // A thin leaf in the middle of the wall, swung against the jamb when open.
        int[] closed = Shapes.boxes(w.get(0, 61, 3), 0);
        assertEquals(1, closed.length);
        assertEquals(3, Shapes.max(closed[0], 2) - Shapes.min(closed[0], 2));
        // Walls either side: a route goes through the door.
        for (int x = -6; x <= 6; x++) for (int y = 61; y <= 63; y++) if (x != 0) w.set(x, y, 3, Blocks.OAK_PLANKS);
        List<int[]> route = PathFinder.find(w, 0, 61, 0, 0, 61, 6, 2000);
        assertFalse(route.isEmpty());
        assertArrayEquals(new int[] {0, 61, 6}, route.get(route.size() - 1));
        short was = swap(w, 0, 61, 3);
        assertEquals(62, Doors.broke(w, 0, 61, 3, was));
        assertEquals(Blocks.AIR, w.get(0, 62, 3), "the upper half goes with the lower");
    }

    /** Breaks the block at (x, y, z) and returns what it was. */
    private static short swap(World w, int x, int y, int z) {
        short b = w.get(x, y, z);
        w.set(x, y, z, Blocks.AIR);
        return b;
    }

    @Test
    void furnitureHasItsShapeAndItsUses() {
        World w = land((x, z) -> 60);
        Actor a = new Actor("p", new Inventory());
        SplittableRandom rnd = new SplittableRandom(1);
        w.set(0, 61, 0, Blocks.STRAW_BED);
        assertTrue(Shapes.max(Shapes.bounds(Shapes.boxes(Blocks.STRAW_BED, 0)), 1) < 10, "a low bed, not a cube");
        a.needs.rest = 0.5;
        assertEquals(Gestures.Use.RESTED, Gestures.use(a, w, 0, 0, 61, 0, false, false, null, null, rnd));
        assertTrue(a.needs.rest > 0.5);
        assertEquals(Gestures.Use.SLEPT, Gestures.use(a, w, 0, 0, 61, 0, false, true, null, null, rnd));
        w.set(1, 61, 0, Blocks.LANTERN);
        assertEquals(15, Blocks.EMISSION[Blocks.LANTERN]);
        assertEquals(Gestures.Use.LIGHT, Gestures.use(a, w, 0, 1, 61, 0, false, false, null, null, rnd));
        assertEquals(Blocks.LANTERN_OFF, w.get(1, 61, 0));
        assertEquals(0, Blocks.EMISSION[Blocks.LANTERN_OFF]);
        w.set(2, 61, 0, (short) (Blocks.OAK_CHAIR + 1));
        assertEquals(Gestures.Use.SAT, Gestures.use(a, w, 0, 2, 61, 0, false, false, null, null, rnd));
        w.set(3, 61, 0, Blocks.BARREL);
        a.needs.water = 5;
        assertEquals(Gestures.Use.DRANK, Gestures.use(a, w, 0, 3, 61, 0, false, false, null, null, rnd));
        assertTrue(a.needs.water > 5);
        assertFalse(Blocks.isSolid(Blocks.RUG), "a rug is walked on");
        assertTrue(Blocks.isFurniture(Blocks.OAK_TABLE) && Blocks.hasBoxes(Blocks.OAK_TABLE));
        assertFalse(PathFinder.standable(w, 0, 62, 0), "nobody walks over the bed");
        for (short f : new short[] {Blocks.OAK_TABLE, Blocks.OAK_CHAIR, Blocks.LANTERN, Blocks.BARREL, Blocks.RUG, Blocks.OAK_DOOR_TOP})
            assertTrue(Blocks.fromState(Blocks.NAMES[f], java.util.Map.of()) > 0);
        assertEquals(Blocks.OAK_DOOR_TOP_Z, Blocks.fromState("oak_door", java.util.Map.of("half", "upper", "axis", "z")));
        assertEquals(Blocks.OAK_DOOR, Blocks.fromState("oak_door", java.util.Map.of("open", "false")), "an old save's door");
    }

    private static Hamlet founded(World w) {
        Hamlet h = new Hamlet();
        h.found(w, -18, -10, ground(w));
        return h;
    }

    @Test
    void theHamletIsFoundedOnLevelledSitesWithItsBuildingsAndPaths(@TempDir Path dir) throws Exception {
        // Rolling ground with a pond: the sites are chosen dry and levelled.
        World w = land((x, z) -> 60 + (int) Math.round(3 * Math.sin(x / 9.0) + 2 * Math.cos(z / 7.0)));
        IntBinaryOperator g0 = ground(w);
        for (int x = 8; x < 16; x++) for (int z = -4; z < 4; z++) w.set(x, g0.applyAsInt(x, z), z, Blocks.WATER);
        Hamlet h = founded(w);
        assertEquals(List.of(Blueprint.COTTAGE, Blueprint.HOUSE, Blueprint.WELL, Blueprint.COOP, Blueprint.APIARY, Blueprint.BAKEHOUSE), h.plans);
        for (int i : new int[] {0, 2, 3, 4}) {
            int[] a = h.huts.get(i);
            int[] nx = h.plan(i).next(w, a[0], a[1], a[2]);
            assertNull(nx, h.plans.get(i) + " stands already; anchor " + java.util.Arrays.toString(a) + " here " + (nx == null ? 0 : w.get(nx[0], nx[1], nx[2]))
                + " steps " + h.plan(i).steps(w, a[0], a[1], a[2]));
        }
        assertEquals(1, h.project(w), "the builder starts with the family's house");
        for (int i = 0; i < h.huts.size(); i++) // no two buildings share ground
            for (int j = 0; j < i; j++) {
                int[] p = Site.box(h.plan(i), h.huts.get(i)), q = Site.box(h.plan(j), h.huts.get(j));
                assertFalse(p[0] <= q[2] && q[0] <= p[2] && p[1] <= q[3] && q[1] <= p[3], h.plans.get(i) + " / " + h.plans.get(j));
            }
        int[] cottage = h.huts.get(0);
        int[] bed = h.plan(0).mark("lit1", cottage);
        assertEquals(bed[0] + 0.5, h.villagers.get(0).homeX, 1e-9, "the hermit sleeps by his bed");
        assertTrue(PathFinder.standable(w, bed[0], bed[1], bed[2]));
        int[] door = h.plan(0).mark("porte", cottage), well = h.plan(2).mark("porte", h.huts.get(2));
        assertFalse(PathFinder.find(w, door[0], door[1], door[2], well[0], well[1], well[2], 6000).isEmpty(), "a way from the door to the well");
        assertFalse(PathFinder.find(w, door[0], door[1], door[2], bed[0], bed[1], bed[2], 6000).isEmpty(), "in through the door to the bed");
        int gravel = 0;
        for (int x = -40; x < 60; x++) for (int z = -40; z < 60; z++) for (int y = 50; y < 75; y++) if (w.get(x, y, z) == Blocks.GRAVEL) gravel++;
        assertTrue(gravel > 10, "paths of gravel");
        Path f = dir.resolve("hamlet.json");
        h.save(f);
        Hamlet back = new Hamlet();
        back.load(f);
        assertEquals(h.plans, back.plans);
        assertArrayEquals(h.field, back.field);
        assertArrayEquals(h.henRun(), back.henRun());
    }

    @Test
    void theBuilderRaisesTheHouseWithoutRobbingTheHamletsFloors() {
        World w = land((x, z) -> 60);
        Hamlet h = founded(w);
        var col = new net.mynecraft.world.Landscape.Column(60, (byte) 0, 64, 1, 12f);
        var eco = new net.mynecraft.world.life.Ecology(42, new net.mynecraft.world.life.Ecosystem(), (x, z) -> col);
        Flows flows = new Flows(eco);
        RecipeBook book = RecipeBook.load(List.of(PackSource.classpath("defaultdata")));
        int[] house = h.huts.get(1);
        int before = h.plan(1).steps(w, house[0], house[1], house[2]);
        int[] cottage = h.huts.get(0);
        short[] floor = new short[h.plan(0).w * h.plan(0).d];
        for (int z = 0; z < h.plan(0).d; z++) for (int x = 0; x < h.plan(0).w; x++) floor[z * h.plan(0).w + x] = w.get(cottage[0] + x, cottage[1], cottage[2] + z);
        for (int i = 0; i < 20 * 60 * 6; i++) h.tick(0.05, w, flows, eco, book, 1, false, 100);
        int after = h.plan(1).steps(w, house[0], house[1], house[2]);
        assertTrue(after < before - 30, "the house rises: " + before + " -> " + after);
        for (int z = 0; z < h.plan(0).d; z++)
            for (int x = 0; x < h.plan(0).w; x++)
                assertEquals(floor[z * h.plan(0).w + x], w.get(cottage[0] + x, cottage[1], cottage[2] + z), "the cottage's floor is left whole");
        assertNull(h.plan(0).next(w, cottage[0], cottage[1], cottage[2]), "and the cottage too");
    }
}
