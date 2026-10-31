package net.mynecraft.world.actor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.world.Items;

/** An actor's person (skills, abilities, needs, meals, plans, hearth) as JSON; the inventory is saved elsewhere. */
public final class ActorCodec {
    private ActorCodec() {}

    public static JsonObject write(Actor a) {
        JsonObject o = new JsonObject();
        o.addProperty("name", a.name);
        JsonObject skills = new JsonObject(), last = new JsonObject();
        for (Skill s : Skill.values()) {
            if (a.skills.xp[s.ordinal()] > 0) skills.addProperty(s.name().toLowerCase(), a.skills.xp[s.ordinal()]);
            if (a.skills.lastPractice[s.ordinal()] > -100) last.addProperty(s.name().toLowerCase(), a.skills.lastPractice[s.ordinal()]);
        }
        o.add("skills", skills);
        o.add("practised", last);
        JsonObject abilities = new JsonObject();
        for (Ability b : Ability.values()) abilities.addProperty(b.name().toLowerCase(), a.skills.ability[b.ordinal()]);
        o.add("abilities", abilities);
        o.addProperty("today", a.skills.today);
        JsonObject needs = new JsonObject();
        needs.addProperty("food", a.needs.food);
        needs.addProperty("water", a.needs.water);
        needs.addProperty("rest", a.needs.rest);
        needs.addProperty("warmth", a.needs.warmth);
        needs.addProperty("rate", a.needs.rate);
        JsonArray meals = new JsonArray();
        for (short m : a.needs.meals) meals.add(Items.name(m));
        needs.add("meals", meals);
        o.add("needs", needs);
        JsonArray plans = new JsonArray();
        for (String p : a.plans) plans.add(p);
        o.add("plans", plans);
        if (a.hasHome()) {
            JsonArray h = new JsonArray();
            h.add(a.homeX);
            h.add(a.homeY);
            h.add(a.homeZ);
            o.add("home", h);
        }
        return o;
    }

    public static void read(Actor a, JsonObject o) {
        if (o.has("name")) a.name = o.get("name").getAsString();
        if (o.has("skills"))
            for (var e : o.getAsJsonObject("skills").entrySet()) {
                Skill s = Skill.byName(e.getKey());
                if (s != null) a.skills.xp[s.ordinal()] = e.getValue().getAsDouble();
            }
        if (o.has("practised"))
            for (var e : o.getAsJsonObject("practised").entrySet()) {
                Skill s = Skill.byName(e.getKey());
                if (s != null) a.skills.lastPractice[s.ordinal()] = e.getValue().getAsDouble();
            }
        if (o.has("abilities"))
            for (var e : o.getAsJsonObject("abilities").entrySet())
                for (Ability b : Ability.values())
                    if (b.name().equalsIgnoreCase(e.getKey())) a.skills.ability[b.ordinal()] = e.getValue().getAsDouble();
        if (o.has("today")) a.skills.today = o.get("today").getAsDouble();
        if (o.has("needs")) {
            JsonObject n = o.getAsJsonObject("needs");
            a.needs.food = n.get("food").getAsDouble();
            a.needs.water = n.get("water").getAsDouble();
            a.needs.rest = n.get("rest").getAsDouble();
            a.needs.warmth = n.get("warmth").getAsDouble();
            a.needs.rate = n.get("rate").getAsDouble();
            a.needs.meals.clear();
            for (var m : n.getAsJsonArray("meals")) {
                short id = Items.fromName(m.getAsString());
                if (id > 0) a.needs.meals.add(id);
            }
        }
        if (o.has("plans")) for (var p : o.getAsJsonArray("plans")) a.plans.add(p.getAsString());
        if (o.has("home")) {
            JsonArray h = o.getAsJsonArray("home");
            a.setHome(h.get(0).getAsDouble(), h.get(1).getAsDouble(), h.get(2).getAsDouble());
        }
    }

    public static void save(Actor a, java.nio.file.Path file) throws java.io.IOException {
        java.nio.file.Files.writeString(file, write(a).toString());
    }

    public static void load(Actor a, java.nio.file.Path file) throws java.io.IOException {
        if (java.nio.file.Files.exists(file)) read(a, JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject());
    }
}
