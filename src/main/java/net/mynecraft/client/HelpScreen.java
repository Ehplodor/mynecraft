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

    void draw(Hud hud) {
        float s = hud.scale, line = hud.lineHeight() + s;
        float keyW = 0, actW = 0;
        int rows = 0;
        for (String[][] sec : SECTIONS)
            for (int i = 1; i < sec.length; i++) {
                keyW = Math.max(keyW, hud.textWidth(sec[i][0]));
                actW = Math.max(actW, hud.textWidth(sec[i][1]));
            }
        // Two columns when they fit, else one long one.
        float colW = keyW + 8 * s + actW;
        int cols = 2 * colW + 24 * s <= hud.width() ? 2 : 1;
        int[] colRows = new int[cols];
        int[] colOf = new int[SECTIONS.length];
        int total = 0;
        for (String[][] sec : SECTIONS) total += sec.length + 1;
        for (int k = 0, c = 0; k < SECTIONS.length; k++) { // fill the first column up to about half
            if (cols == 2 && c == 0 && colRows[0] > 0 && colRows[0] + SECTIONS[k].length > (total + 1) / 2) c = 1;
            colOf[k] = c;
            colRows[c] += SECTIONS[k].length + 1;
        }
        for (int r : colRows) rows = Math.max(rows, r);
        float w = cols * colW + (cols - 1) * 16 * s, h = (rows + 2) * line;
        float x0 = (hud.width() - w) / 2, y0 = Math.max(4 * s, (hud.height() - h) / 2);
        hud.rect(x0 - 8 * s, y0 - 6 * s, w + 16 * s, h + 12 * s, 0.04f, 0.05f, 0.07f, 0.85f);
        hud.textCentered("Aide - touches et raccourcis", hud.width() / 2f, y0, 1, 0.95f, 0.7f);
        float[] y = new float[cols];
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
