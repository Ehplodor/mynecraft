package net.mynecraft.client;

/**
 * The help screen (F1): every key and shortcut, grouped by where they apply. Letters are named by
 * their QWERTY position for movement (ZQSD on AZERTY) and by the character typed for the others.
 */
final class HelpScreen {
    boolean open;

    /** Sections: a title, then key / action pairs. */
    private static final String[][][] SECTIONS = {
        {{"Déplacements"},
            {"Z Q S D (W A S D)", "avancer, gauche, reculer, droite"},
            {"Espace", "sauter (en vol : monter)"},
            {"Maj", "descendre en vol"},
            {"Ctrl", "courir"},
            {"F", "voler (créatif)"},
            {"Souris", "regarder"}},
        {{"Actions"},
            {"Clic gauche", "casser"},
            {"Clic droit", "poser"},
            {"1-9 / molette", "case de la barre (créatif : molette = bloc)"},
            {"E", "inventaire et fabrication"},
            {"R", "réapparaître (après la mort)"}},
        {{"Inventaire (E)"},
            {"Clic / Maj+clic", "prendre, poser / transférer"},
            {"Double-clic", "regrouper les mêmes objets"},
            {"Glisser", "répartir (clic droit : un par case)"},
            {"1-9 sur une case", "échanger avec la barre"},
            {"T", "trier"},
            {"R / U", "recettes / utilisations de l'objet"},
            {"Clic sur la recherche", "taper un nom (Entrée, Échap)"},
            {"E / Échap", "fermer"}},
        {{"Carte et outils"},
            {"M", "minicarte : taille suivante"},
            {"J", "carte du monde"},
            {"   molette / glisser", "zoomer / déplacer"},
            {"   clic droit", "poser / retirer un repère"},
            {"   M / C", "couche suivante / recentrer"},
            {"B", "repère à ses pieds"},
            {"N", "carnet du naturaliste"}},
        {{"Jeu"},
            {"Échap", "pause (graine, volume...)"},
            {"F1", "cette aide"},
            {"F3", "écran de débogage"},
            {"F4", "créatif / survie"}},
    };

    /**
     * Fits any window: tries every column count, keeps the one that allows the largest text, and
     * shrinks the GUI scale below its usual value when even that does not fit (whole pixels when it can).
     */
    void draw(Hud hud) {
        float saved = hud.scale;
        try {
            hud.scale = 1; // measure in font pixels
            float line = hud.lineHeight() + 1, keyW = 0, actW = 0, total = 0;
            float[] secH = new float[SECTIONS.length];
            for (int k = 0; k < SECTIONS.length; k++) {
                for (int i = 1; i < SECTIONS[k].length; i++) {
                    keyW = Math.max(keyW, hud.textWidth(SECTIONS[k][i][0]));
                    actW = Math.max(actW, hud.textWidth(SECTIONS[k][i][1]));
                }
                total += secH[k] = (SECTIONS[k].length + 0.5f) * line;
            }
            float colW = keyW + 8 + actW, titleW = hud.textWidth(TITLE);
            int bestCols = 1;
            float best = -1;
            int[] colOf = new int[SECTIONS.length], bestOf = null;
            for (int n = 1; n <= SECTIONS.length; n++) {
                float colH = columns(secH, total / n, n, colOf);
                float w = Math.max(titleW, n * colW + (n - 1) * 16) + 16, h = colH + 2.5f * line + 12;
                float fit = Math.min(hud.width() / w, hud.height() / h);
                if (fit > best * 1.001f) {
                    best = fit;
                    bestCols = n;
                    bestOf = colOf.clone();
                }
            }
            float s = Math.min(saved, best);
            hud.scale = s >= 1 ? (float) Math.floor(s) : s;
            draw(hud, bestCols, bestOf, keyW, colW);
        } finally {
            hud.scale = saved;
        }
    }

    /** Puts the sections in order into at most {@code n} columns of about {@code target} high; returns the tallest. */
    private static float columns(float[] secH, float target, int n, int[] colOf) {
        float tallest = 0, h = 0;
        for (int k = 0, c = 0; k < secH.length; k++) {
            if (h > 0 && c < n - 1 && h + secH[k] / 2 > target) {
                c++;
                h = 0;
            }
            colOf[k] = c;
            tallest = Math.max(tallest, h += secH[k]);
        }
        return tallest;
    }

    private static final String TITLE = "Aide - touches et raccourcis";

    private void draw(Hud hud, int cols, int[] colOf, float keyW, float colW) {
        float s = hud.scale, line = hud.lineHeight() + s;
        keyW *= s;
        colW *= s;
        float[] y = new float[cols];
        float rows = 0;
        for (int k = 0; k < SECTIONS.length; k++) y[colOf[k]] += (SECTIONS[k].length + 0.5f) * line;
        for (float v : y) rows = Math.max(rows, v);
        float w = cols * colW + (cols - 1) * 16 * s, h = rows + 2.5f * line;
        float x0 = (hud.width() - w) / 2, y0 = (hud.height() - h) / 2;
        float bw = Math.max(w, hud.textWidth(TITLE));
        hud.rect((hud.width() - bw) / 2 - 8 * s, y0 - 6 * s, bw + 16 * s, h + 12 * s, 0.04f, 0.05f, 0.07f, 0.85f);
        hud.textCentered(TITLE, hud.width() / 2f, y0, 1, 0.95f, 0.7f);
        java.util.Arrays.fill(y, y0 + line * 1.5f);
        for (int k = 0; k < SECTIONS.length; k++) {
            int c = colOf[k];
            float x = x0 + c * (colW + 16 * s);
            hud.text(SECTIONS[k][0][0], x, y[c], 0.6f, 0.85f, 1);
            y[c] += line;
            for (int i = 1; i < SECTIONS[k].length; i++) {
                hud.text(SECTIONS[k][i][0], x, y[c], 1, 0.9f, 0.55f);
                hud.text(SECTIONS[k][i][1], x + keyW + 8 * s, y[c], 0.9f, 0.9f, 0.9f);
                y[c] += line;
            }
            y[c] += line * 0.5f;
        }
        hud.textCentered("F1 / Échap : fermer", hud.width() / 2f, y0 + h - line, 0.6f, 0.6f, 0.6f);
    }
}
