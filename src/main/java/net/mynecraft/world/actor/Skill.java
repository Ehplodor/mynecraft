package net.mynecraft.world.actor;

import static net.mynecraft.world.actor.Ability.*;

/** A domain of know-how, learnt by practice; each trains two abilities. */
public enum Skill {
    MINING("Minage", STRENGTH, ENDURANCE),
    WOODCUTTING("Bûcheronnage", STRENGTH, ENDURANCE),
    BUILDING("Construction", DEXTERITY, AESTHETIC),
    CRAFTING("Artisanat", DEXTERITY, PATIENCE),
    FORAGING("Cueillette", PERCEPTION, MEMORY),
    FISHING("Pêche", PATIENCE, PERCEPTION),
    HUNTING("Chasse", PERCEPTION, DEXTERITY),
    COOKING("Cuisine", PATIENCE, AESTHETIC),
    FARMING("Culture", ENDURANCE, PATIENCE),
    HUSBANDRY("Élevage", PATIENCE, PERCEPTION),
    MILLING("Meunerie", ENDURANCE, PATIENCE),
    SMITHING("Forge", STRENGTH, DEXTERITY),
    TANNING("Tannerie", ENDURANCE, PATIENCE),
    GLASSWORK("Verrerie", DEXTERITY, AESTHETIC),
    POTTERY("Poterie", DEXTERITY, AESTHETIC),
    WEAVING("Tissage", DEXTERITY, PATIENCE),
    DYEING("Teinture", PERCEPTION, AESTHETIC),
    ART("Art", AESTHETIC, DEXTERITY),
    MUSIC("Musique", AESTHETIC, PATIENCE);

    public final String displayName;
    public final Ability primary, secondary;

    Skill(String displayName, Ability primary, Ability secondary) {
        this.displayName = displayName;
        this.primary = primary;
        this.secondary = secondary;
    }

    /** Skill for a data pack name ("cooking"), or null. */
    public static Skill byName(String name) {
        for (Skill s : values()) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }
}
