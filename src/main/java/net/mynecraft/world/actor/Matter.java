package net.mynecraft.world.actor;

/** What flows between the land and the people: taken from a patch, or returned to it. */
public enum Matter {
    WOOD("bois"), STONE("pierre"), SOIL("terre et sable"), ORE("minerai"), PLANTS("plantes"), FRUIT("fruits et baies"),
    GAME("gibier"), FISH("poissons"), WATER("eau et glace"), ASH("cendres"), COMPOST("compost"), SLAG("scories"),
    WASTEWATER("eaux usées");

    public final String displayName;

    Matter(String displayName) {
        this.displayName = displayName;
    }

    public static Matter byName(String name) {
        for (Matter m : values()) if (m.name().equalsIgnoreCase(name)) return m;
        return null;
    }
}
