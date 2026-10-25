package net.mynecraft.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pause screen (Esc) as a menu clicked with the mouse: resume, respawn, and every way to start
 * again: reload this world, a new world with a random seed, with this world's seed, with a typed
 * seed (digits, or any text, hashed like vanilla), open another saved world, save and quit.
 * Starting another world relaunches the game with the new arguments (see {@link Main}).
 */
final class PauseMenu {
    enum Action { NONE, RESUME, RESPAWN, RELOAD, NEW_RANDOM, NEW_SAME_SEED, NEW_TYPED_SEED, OPEN_WORLD, QUIT }

    private record Button(String label, Action action) {}

    private final Path current;
    private final long seed;
    private final List<Path> worlds = new ArrayList<>();
    private int worldIndex;
    /** Seed being typed (the field has the focus while {@link #typing}). */
    final StringBuilder seedText = new StringBuilder();
    boolean typing;

    PauseMenu(Path current, long seed) {
        this.current = current;
        this.seed = seed;
        Path saves = current.toAbsolutePath().getParent();
        if (saves != null && Files.isDirectory(saves)) {
            try (var list = Files.list(saves)) {
                list.filter(p -> Files.exists(p.resolve("level.dat")) && !p.toAbsolutePath().equals(current.toAbsolutePath()))
                    .sorted().forEach(worlds::add);
            } catch (IOException e) {
                System.err.println("[menu] cannot list " + saves + ": " + e.getMessage());
            }
        }
    }

    private List<Button> buttons() {
        List<Button> b = new ArrayList<>();
        b.add(new Button("Reprendre", Action.RESUME));
        b.add(new Button("Réapparaître au point de départ", Action.RESPAWN));
        b.add(new Button("Recharger ce monde (" + current.getFileName() + ")", Action.RELOAD));
        b.add(new Button("Nouveau monde : graine aléatoire", Action.NEW_RANDOM));
        b.add(new Button("Nouveau monde : même graine (" + seed + ")", Action.NEW_SAME_SEED));
        String field = typing ? seedText + "_" : seedText.length() == 0 ? "cliquer pour saisir" : seedText.toString();
        b.add(new Button("Nouveau monde, graine : " + field, Action.NEW_TYPED_SEED));
        if (!worlds.isEmpty()) {
            b.add(new Button("Ouvrir : " + worlds.get(worldIndex).getFileName() + "   (clic droit : monde suivant)", Action.OPEN_WORLD));
        }
        b.add(new Button("Sauvegarder et quitter", Action.QUIT));
        return b;
    }

    /** The world chosen with OPEN_WORLD. */
    Path chosenWorld() {
        return worlds.get(worldIndex);
    }

    /** The typed seed: a number as is, any other text hashed like vanilla does. */
    long typedSeed() {
        String s = seedText.toString().trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    private float[] layout(Hud hud, int count) {
        float w = Math.min(hud.width() * 0.8f, 230 * hud.scale), h = hud.lineHeight() + 6 * hud.scale, gap = 3 * hud.scale;
        float top = hud.height() / 2f - count * (h + gap) / 2f + hud.lineHeight();
        return new float[] {(hud.width() - w) / 2, top, w, h, gap};
    }

    /** Draws the menu; {@code mx, my} is the mouse in framebuffer pixels. */
    void draw(Hud hud, double mx, double my) {
        List<Button> b = buttons();
        float[] l = layout(hud, b.size());
        hud.rect(0, 0, hud.width(), hud.height(), 0, 0, 0, 0.55f);
        hud.textCentered("Pause", hud.width() / 2f, l[1] - 2.5f * hud.lineHeight(), 1, 1, 1);
        for (int i = 0; i < b.size(); i++) {
            float y = l[1] + i * (l[3] + l[4]);
            boolean hover = mx >= l[0] && mx < l[0] + l[2] && my >= y && my < y + l[3];
            boolean field = b.get(i).action == Action.NEW_TYPED_SEED && typing;
            float shade = hover || field ? 0.45f : 0.25f;
            hud.rect(l[0], y, l[2], l[3], shade, shade, shade + 0.05f, 0.85f);
            hud.textCentered(b.get(i).label, hud.width() / 2f, y + 3 * hud.scale, 1, 1, hover ? 0.6f : 1);
        }
        hud.textCentered("Échap : reprendre | Entrée : valider la graine", hud.width() / 2f,
            l[1] + b.size() * (l[3] + l[4]) + hud.lineHeight(), 0.75f, 0.75f, 0.75f);
    }

    /** A click at (mx, my): the action chosen, or NONE. Right click cycles the world to open. */
    Action click(Hud hud, double mx, double my, boolean right) {
        List<Button> b = buttons();
        float[] l = layout(hud, b.size());
        for (int i = 0; i < b.size(); i++) {
            float y = l[1] + i * (l[3] + l[4]);
            if (mx < l[0] || mx >= l[0] + l[2] || my < y || my >= y + l[3]) continue;
            Action a = b.get(i).action;
            if (a == Action.OPEN_WORLD && right) {
                worldIndex = (worldIndex + 1) % worlds.size();
                return Action.NONE;
            }
            if (a == Action.NEW_TYPED_SEED && (!typing || seedText.length() == 0)) {
                typing = true; // first click focuses the field; Enter or a second click starts
                return Action.NONE;
            }
            typing = false;
            return a;
        }
        typing = false;
        return Action.NONE;
    }

    /** A new world folder next to the current one: monde-2, monde-3... */
    Path newWorldDir() {
        Path saves = current.toAbsolutePath().getParent();
        for (int i = 2; ; i++) {
            Path p = saves.resolve("monde-" + i);
            if (!Files.exists(p)) return p;
        }
    }
}
