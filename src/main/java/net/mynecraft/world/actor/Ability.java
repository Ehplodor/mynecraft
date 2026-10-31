package net.mynecraft.world.actor;

/**
 * What the body and mind can do, shared by the player and the inhabitants. Each grows slowly with
 * the skills that use it and gives the quality of the gestures (speed, precision, finesse).
 */
public enum Ability {
    STRENGTH("Force"), ENDURANCE("Endurance"), DEXTERITY("Adresse"), PERCEPTION("Perception"),
    PATIENCE("Patience"), MEMORY("Mémoire des lieux"), AESTHETIC("Sens esthétique");

    public final String displayName;

    Ability(String displayName) {
        this.displayName = displayName;
    }
}
