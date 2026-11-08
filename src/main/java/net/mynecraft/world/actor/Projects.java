package net.mynecraft.world.actor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Species;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's projects (aspirations, E5.1): a project is proposed by an event (a complaint, a sight),
 * accepted with a key on the notebook's page, followed by its own measure, then accomplished. The
 * first is the river's (A2): bring the fisher's river back to life, clean water and trout spawning
 * again for three days in a row.
 */
public final class Projects {
    public enum State { PROPOSED, ACCEPTED, DONE }

    public static final class Project {
        public final String id, title, goal;
        public State state = State.PROPOSED;
        /** Days the goal has held in a row (for goals that must last). */
        public double streak;
        /** Where the project watches (x, z), and the last measure, for the notebook. */
        public double x, z;
        public String status = "";

        Project(String id, String title, String goal) {
            this.id = id;
            this.title = title;
            this.goal = goal;
        }
    }

    public static final String RIVER = "A2";
    /** Clean water: pollution below this, the trout spawning, at least this many trout, for {@link #RIVER_DAYS} days. */
    public static final double RIVER_CLEAN = 0.1, RIVER_TROUT = 2, RIVER_DAYS = 3;

    private final List<Project> list = new ArrayList<>();

    public List<Project> all() {
        return list;
    }

    public Project get(String id) {
        for (Project p : list) if (p.id.equals(id)) return p;
        return null;
    }

    /** Proposes a project once (null if it was already proposed). */
    public Project propose(String id, String title, String goal, double x, double z) {
        if (get(id) != null) return null;
        Project p = new Project(id, title, goal);
        p.x = x;
        p.z = z;
        list.add(p);
        return p;
    }

    /** Accepts the first proposed project; returns it, or null if none waits. */
    public Project acceptNext() {
        for (Project p : list)
            if (p.state == State.PROPOSED) { p.state = State.ACCEPTED; return p; }
        return null;
    }

    /** Proposes A2, when the fisher complains of his river at (x, z). */
    public Project proposeRiver(double x, double z) {
        return propose(RIVER, "Ramener la rivière à la vie",
            "Une eau propre et des truites qui fraient à nouveau, trois jours durant, chez le pêcheur.", x, z);
    }

    /**
     * Follows A2 over {@code days} on the patch it watches; true when it was just accomplished. Counts
     * only once accepted, and the streak starts again whenever the water fouls or the trout are gone.
     */
    public boolean river(Ecology.Patch p, double days) {
        Project a = get(RIVER);
        if (a == null || a.state != State.ACCEPTED || p == null) return false;
        double trout = p.pop[Species.TROUT.ordinal()];
        boolean clean = p.pollution < RIVER_CLEAN && !p.spawnStopped, lively = trout >= RIVER_TROUT;
        a.streak = clean && lively ? a.streak + days : 0;
        a.status = String.format("pollution %.0f %%, %.1f truites, %s - %.1f / %.0f jours", 100 * p.pollution, trout,
            p.spawnStopped ? "pas de frai" : "frai possible", Math.min(a.streak, RIVER_DAYS), RIVER_DAYS);
        if (a.streak < RIVER_DAYS) return false;
        a.state = State.DONE;
        a.status = "Accompli : la rivière revit.";
        return true;
    }

    public void save(Path file) throws IOException {
        JsonArray arr = new JsonArray();
        for (Project p : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id);
            o.addProperty("title", p.title);
            o.addProperty("goal", p.goal);
            o.addProperty("state", p.state.name());
            o.addProperty("streak", p.streak);
            o.addProperty("x", p.x);
            o.addProperty("z", p.z);
            o.addProperty("status", p.status);
            arr.add(o);
        }
        Files.writeString(file, arr.toString());
    }

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        list.clear();
        for (var e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            Project p = new Project(o.get("id").getAsString(), o.get("title").getAsString(), o.get("goal").getAsString());
            p.state = State.valueOf(o.get("state").getAsString());
            p.streak = o.get("streak").getAsDouble();
            p.x = o.get("x").getAsDouble();
            p.z = o.get("z").getAsDouble();
            p.status = o.has("status") ? o.get("status").getAsString() : "";
            list.add(p);
        }
    }
}
