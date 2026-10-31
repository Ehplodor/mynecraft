package net.mynecraft.world.actor;

import static net.mynecraft.world.actor.Skill.*;

/**
 * A trade the others recognise: a combination of skills. Its level is the lowest of them, and gives
 * the title (apprenti, compagnon, maître). Nobody picks a trade: it is read from what one practises.
 */
public enum Job {
    GATHERER("cueilleur", FORAGING), HERBALIST("herboriste", FORAGING, COOKING), FISHER("pêcheur", FISHING),
    HUNTER("chasseur", HUNTING), COOK("cuisinier", COOKING), WOODCUTTER("bûcheron", WOODCUTTING),
    MINER("mineur", MINING), BUILDER("bâtisseur", BUILDING), CARPENTER("charpentier", BUILDING, WOODCUTTING),
    MASON("maçon", BUILDING, MINING), ARCHITECT("architecte", BUILDING, ART), PEASANT("paysan", FARMING),
    GARDENER("jardinier", FARMING, FORAGING), HERDER("éleveur", HUSBANDRY), BEEKEEPER("apiculteur", HUSBANDRY, FORAGING),
    MILLER("meunier", MILLING), BAKER("boulanger", MILLING, COOKING), SMITH("forgeron", SMITHING),
    FOUNDER("fondeur", SMITHING, MINING), GLASSMAKER("verrier", GLASSWORK), TANNER("tanneur", TANNING),
    ENGINEER("ingénieur", MILLING, BUILDING, SMITHING), POTTER("potier", POTTERY), WEAVER("tisserand", WEAVING),
    DYER("teinturier", DYEING), ARTIST("artiste", ART), MUSICIAN("musicien", MUSIC), ARTISAN("artisan", CRAFTING);

    public final String displayName;
    public final Skill[] skills;

    Job(String displayName, Skill... skills) {
        this.displayName = displayName;
        this.skills = skills;
    }

    /** Title for a level: none below 1, apprenti 1..3, compagnon 4..7, maître 8+. */
    public static String title(int level) {
        return level >= 8 ? "maître" : level >= 4 ? "compagnon" : level >= 1 ? "apprenti" : "";
    }
}
