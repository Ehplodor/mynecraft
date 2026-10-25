# Feuille de route

## ✅ M0 — Monde voxel affiché
- Projet Gradle + LWJGL 3 (OpenGL 3.3 core) + JOML
- Génération procédurale, chunks 16×128×16 (8 sections)
- Greedy meshing, sommets 32 bits, index buffer partagé, frustum culling, fog
- Pipeline multi-thread (génération + maillage hors thread de rendu)
- Mesures M0 (CPU conteneur, JIT froid) : ~0,1 ms/chunk généré, ~0,25 ms/section maillée, ~33 quads/section

## ✅ M1 — Resource packs
- Packs Java (dossier/zip, y compris zip avec dossier englobant), empilés avec priorité
- Résolution `blockstates → models → textures` avec héritage, `#refs`, `tintindex`
- Texture array GPU + UV dérivées de la position : le greedy meshing est conservé (pas d'atlas)
- Pack par défaut original, généré par script
- Sommet toujours sur 32 bits (couche de texture 10 bits + bit de teinte)
- Reste à faire : modèles non cubiques (dalles, escaliers, plantes), rotations, animations, couleurs de biome

## ✅ M2 — Interaction
- Joueur AABB 0,6×1,8, gravité, saut (~1,25 bloc), mode vol, pas physique fixe 120 Hz
- Raycast DDA (portée 5), casser (bedrock incassable) / poser (interdit dans le joueur), répétition au maintien
- Remaillage incrémental : seule la section touchée (+ voisines si le bloc est en bordure)
- File dédiée aux modifications du joueur (jamais bloquée par le chargement), uploads prioritaires
- Versionnage par section : un maillage obsolète ne peut pas écraser un plus récent
- Réticule et contour du bloc visé

## ✅ M3 — Monde infini & persistance
- Streaming autour du joueur : priorité par distance, tâches en vol bornées, hystérésis rendu (R) / masquage (R+1) / déchargement (R+3)
- Un chunk n'est maillé que si ses 4 voisins sont chargés (bords corrects du premier coup)
- NBT complet (12 types, gzip), fichiers région Anvil (lecture gzip/zlib/brut, écriture zlib, réallocation de secteurs)
- Codec de chunk : lecture 1.18+ et 1.16–1.17, écriture 1.21 (palette + `long[]`)
- Sauvegarde des seuls chunks modifiés, file IO unique ordonnée, autosave 30 s, `level.dat` atomique
- Mondes vanilla en lecture seule par défaut ; correspondance approximative des blocs inconnus
- Reste à faire : hauteur -64..319, compression LZ4 (1.20.5+ optionnelle) et chunks externes `.mcc`,
  validation d'une sauvegarde Mynecraft ouverte par le jeu officiel

## ✅ M4 — Plus de blocs, transparence, lumière
- Registre de 16 blocs (tableaux par propriété) : eau, bûche, feuilles, planches, pierre taillée, verre,
  herbes, coquelicot, glowstone, gravier
- 3 passes : opaque trié avant→arrière (rejet précoce en profondeur), cutout (programme séparé pour que
  l'opaque ne paie pas `discard`), translucide trié arrière→avant sans écriture de profondeur ;
  un seul VBO par section (`glDrawElementsBaseVertex`)
- Lumière ciel + blocs 4+4 bits : BFS incrémental (file d'ajout / file de retrait), propagation aux bords à
  l'arrivée d'un chunk, sections touchées remaillées ; tableaux de lumière nuls tant que « plein ciel »
- AO par sommet façon vanilla avec diagonale choisie contre l'anisotropie ; le greedy ne fusionne qu'à
  lumière et AO identiques
- Sommet 8 octets (position/face/texture/teinte + lumière/AO)
- Génération : eau au niveau de la mer, gravier, arbres, herbes, fleurs (décor confiné au chunk)
- Teintes herbe / feuillage / eau, modèles `cross` / `tinted_cross` / `leaves` / `cube_column`
- Mesures (CPU conteneur) : génération + lumière ~0,34 ms/chunk, maillage ~0,33 ms/section,
  ~110 quads/section (plantes et AO limitent la fusion)
- Reste à faire : surface de l'eau abaissée, cycle jour/nuit (uniforme déjà séparé ciel/blocs),
  lumière lisse, couleurs de biome, propriétés de blocs (orientation des bûches…)

## ✅ M5 — Performance du rendu
- Un seul VBO partagé (allocateur first-fit + fusion, agrandissement par copie GPU) et un seul VAO
- **1 appel de dessin par passe** (`glMultiDrawElementsBaseVertex`, OpenGL 3.3) au lieu d'un par section :
  chaque sommet porte sa section (x, z mod 64, y), le shader reconstruit la position relative à la caméra
- Occlusion culling « cave culling » : connectivité des 6 faces par section (15 bits, calculée au maillage),
  parcours en largeur depuis la caméra sans demi-tour, combiné au frustum
- Mesures (rayon 12, même vue, images identiques au pixel) : 543 → 324 sections dessinées en surface (−40 %),
  527 → 1 caméra dans la roche
- Lumière de jointure des chunks découpée dans le temps (budget 2 ms/image)
- Correctif : un chunk n'est maillé qu'avec ses 8 voisins chargés (AO des coins exacte, maillage déterministe)
- Reste à faire : mesurer le temps GPU/CPU par image sur une vraie carte (requêtes timer GL),
  lumière de jointure sur un thread dédié

## ✅ M6 — Monde complet et rendu vivant
- Hauteur -64..319 (24 sections), sections Anvil Y -4..19 lues et écrites directement
- Sections enfouies (opaques et entourées d'opaques) sautées sans maillage ; bordure du maillage lue via
  les 3×3 chunks voisins (plus de recherche par case) : 0,25 → 0,09 ms/section en moyenne
- Lumière lisse : somme exacte des 4 cellules autour du sommet (6+6 bits), cellules opaques remplacées
  comme en vanilla ; clé de fusion 64 bits (bloc, AO, 4 lumières de coin) ; diagonale choisie sur lumière + AO
- Cycle jour/nuit (24 000 ticks, `DayTime` dans `level.dat`), ciel et brouillard suivent, lumière du ciel
  modulée dans le shader (aucun remaillage)
- Couleurs de biome : température/humidité par bruit, colormaps du pack (`colormap/grass.png`,
  `foliage.png`), carte GPU 512×512 circulaire échantillonnée en linéaire (mélange gratuit entre colonnes)
- Surface de l'eau abaissée de 2/16 (1 bit du sommet)
- Coût mesuré : +11 % de quads par section (la lumière lisse limite un peu la fusion)
- Reste à faire : biomes lus depuis les sauvegardes vanilla

## ✅ M7 — Performance de génération, ciel, états de blocs
- Génération : sections sous la roche remplies d'un coup ; lumière : tableau « sombre » partagé
  (copie à l'écriture) pour les sections enfouies ; jointure de chunk lisant les tableaux directement
  → jointure 1,6 → 0,04 ms/chunk, génération + lumière 0,53 → 0,30 ms/chunk (maillage identique)
- Overlay teinté des côtés d'herbe composité dans le shader (couche + 1), sans quad supplémentaire
- Soleil, lune (8 phases), nuages plats à y = 192 (UV en double précision, dérive au vent)
- États de blocs : un id par état, variantes de blockstate par propriétés, rotations `x`/`y` appliquées
  aux faces avec textures pré-tournées dans le texture array (aucun bit de sommet en plus)
- Bûches orientées (`axis`), dalles bas/haut/double (`type`), lues et écrites dans les sauvegardes ;
  les familles vanilla gardent leurs propriétés (`birch_log[axis=x]` → bûche horizontale)
- Collisions AABB sur les boîtes réelles + montée automatique de 0,6 bloc ; règles de pose vanilla
- Reste à faire : escaliers, `uvlock`

## ✅ M8 — Mesure, visée précise, nouveaux blocs
- Mode `--benchmark N` : vol scripté à 20 blocs/s, FPS, percentiles de temps d'image, temps GPU
  (requêtes `GL_TIME_ELAPSED` en anneau, sans attente), coût CPU de la sélection des sections, mémoire
- Parcours d'occlusion sans hachage ni boxing (grille indexée x, z mod 64) : 1,28 → 0,15 ms/image
- Visée précise des demi-blocs (rayon contre la vraie boîte), contour ajusté à la forme
- 18 états de plus : granite, diorite, andésite, deepslate, minerais (charbon, fer, or, diamant),
  bouleau et épicéa (bûches 3 axes, planches, feuilles) ; molette = tous les blocs
- Génération : deepslate sous y = 0, filons aux hauteurs vanilla, essences d'arbres selon le climat
- Familles vanilla : autres essences → chêne, minerais deepslate → minerai, `tuff` → deepslate…
- Mesures (rendu logiciel llvmpipe, non représentatif d'un GPU) : ~38 FPS, p99 34 ms ; blocs + lumière
  34 Mio pour 780 chunks, tampon GPU 10 Mio
- Décision : pas de compression par palette des blocs pour l'instant (≈ 20 Mio gagnés au prix des
  chemins les plus chauds : maillage, lumière)
- Reste à faire : couleurs fixes des feuilles de bouleau et d'épicéa

## ✅ M9 — Grottes, montagnes, barre d'objets
- Grottes : tunnels « spaghetti » (deux champs de bruit 3D proches de zéro) et cavernes profondes
  « cheese » ; bruit échantillonné sur une grille 4×8×4 puis interpolé (x/z une fois par colonne,
  y par bloc, 2ᵉ champ évalué seulement près du 1er) ; ~3,5 % du sous-sol creusé ; pas de creusement
  sous l'eau (pas encore d'écoulement des fluides)
- Passe verticale de lumière qui saute les sections vides en plein soleil ; profilage JFR :
  génération + lumière 0,71 → 0,49 ms/chunk à monde égal
- Montagnes : masque très basse fréquence + bruit « ridged », sommets ~200, roche nue au-dessus de 135
- Barre d'objets à l'écran (icônes isométriques, dalles en demi-hauteur), molette = bloc de l'emplacement
- Option de débogage `--fullbright`
- Coût mesuré des grottes : ~160 quads/section (vs ~52), ~0,9 à 1,1 M quads chargés, tampon GPU
  ~30 Mio ; sous terre, l'occlusion ne dessine que ~1 % des sections du champ de vision
- Reste à faire : surfaces de fluide inclinées

## ✅ M10 — Mémoire GPU divisée par deux, texte, F3, pause
- Quads de 16 octets (un enregistrement RGBA32UI lu par le vertex shader via un texture buffer,
  « vertex pulling ») au lieu de 4 sommets de 8 octets ; tampon d'indices partagé conservé pour rester
  à 4 exécutions du shader par quad (cache post-transformation)
- Même scène, même image (30 pixels de bord différents sur 921 600) : tampon GPU 29,6 → 14,8 Mio
- Mesuré sur llvmpipe (rendu logiciel) : −5 % de FPS (lecture de texel coûteuse sur CPU) ; une
  variante sans indices (6 exécutions/quad) faisait −16 % et a été écartée. À mesurer sur un vrai GPU
- Police bitmap au format vanilla (`font/default.json`, `ascii.png`), police 5×7 originale avec accents
- Écran F3 (FPS, temps GPU par requêtes timer, sections, quads, chunks, mémoire, position, bloc visé,
  lumière, climat, heure), pause sur Échap, nom du bloc choisi au-dessus de la barre

## Pistes d'optimisation continues
- Multi-draw indirect (`glMultiDrawElementsIndirect`) + un seul gros VBO → 1 draw call
- Occlusion culling par graphe de visibilité des sections (approche « cave culling »)
- Palette par section (4 bits/bloc) pour la mémoire, ambient occlusion baked dans les 4 bits restants du sommet
- Benchmarks JMH en CI pour détecter les régressions

## ✅ M11 — Fluides
- États de fluide = propriété vanilla `level` : eau source / 1..7 / chute, lave source / 2 / 4 / 6 / chute ;
  lus et écrits dans les sauvegardes (niveaux 8..15 → chute, niveaux de lave impairs arrondis)
- Simulation par ticks programmés (20/s, gelés en pause) : eau toutes les 5, lave toutes les 30 ;
  une file FIFO par fluide (délai constant ⇒ déjà triée), seules les cases proches d'un changement
  sont évaluées, budget de 4 096 mises à jour par tick
- Règles : chute prioritaire, sinon étalement en perdant 1 niveau (2 pour la lave), assèchement quand
  la source disparaît, source infinie entre deux sources, eau + lave → obsidienne / pierre taillée / pierre
- Rendu : hauteur de surface selon le niveau, rangée dans des bits libres de l'enregistrement de quad
  (toujours 16 o) ; faces entre fluides cachées sauf « marche » vers une surface plus basse ; lave
  lumineuse (15), opaque mais pas cube plein
- Joueur : nage (flottabilité, traînée, Espace pour remonter), plus lent dans la lave
- Génération : lacs de lave au fond des grottes (y ≤ −55) ; obsidienne ajoutée au registre
- Bugs trouvés par les tests : une chute (niveau 8) était prise pour un niveau trop éloigné et effacée ;
  un niveau horizontal 8 était confondu avec « chute » et entretenait l'eau sans source
- Reste à faire : surfaces inclinées (hauteur par coin), courant qui pousse le joueur, priorité
  d'écoulement vers les pentes, lave qui brûle

## ✅ M12 — Bases de la survie
- Modes de jeu survie / créatif (`F4`, `--creative`), sauvegardés comme `playerGameType` ; vol réservé au créatif
- Vie (20 demi-cœurs) : chute au-delà de 3 blocs (annulée par l'eau), lave (4 par ½ s), noyade
  (300 ticks d'air puis 2/s), régénération lente sans faim ; mort, écran dédié, réapparition au spawn
- Inventaire 36 × 64 (barre = emplacements 0..8) ; butins vanilla (pierre → pierre taillée,
  herbe → terre, feuilles/verre/herbes → rien, dalle double → 2) ; pose consommée
- Minage chronométré : dureté vanilla × 1,5 s à la main, barre de progression ; bedrock et fluides
  incassables
- HUD : cœurs, bulles d'air, quantités ; sprites du pack au format 1.20.2+ (`gui/sprites/hud/…`),
  sprites originaux dans le pack par défaut ; flash rouge sur dégât
- `level.dat` : `Health`, `Air`, `Inventory` (format 1.21 `{Slot, id, count}`, ancien `Count` lu),
  `SelectedItemSlot`, `SpawnX/Y/Z`
- Simplifications : pas d'outils (tout se mine à la main et donne son butin), pas de faim, pas
  d'objets non-blocs, inventaire conservé à la mort

## ✅ M13 — Inventaire, fabrication et data packs
- Data packs au format vanilla : `data/<ns>/recipe/*.json` et `tags/item/*.json` (et les dossiers au
  pluriel d'avant 1.21), lus depuis le pack intégré, `<monde>/datapacks/` (comme vanilla) et `--datapack` ;
  remplacement fichier par fichier dans l'ordre des packs
- Recettes `crafting_shaped` (avec miroir) et `crafting_shapeless` ; ingrédients en id, `#tag`, objets
  `{"item"}`/`{"tag"}` ou listes ; résultats `id` (1.20.5+) ou `item` ; recettes à objets non-blocs ignorées
  et signalées
- Écran d'inventaire (`E`) : 36 emplacements + grille 2×2 ; établi (nouveau bloc) : grille 3×3 ;
  règles de clic vanilla (prendre, poser, fusionner, échanger, clic droit, Maj-clic, Maj-clic sur le
  résultat = tout fabriquer) ; fermer rend le contenu de la grille et du curseur
- Créatif : `E` ouvre la palette de tous les blocs, un clic remplit l'emplacement choisi
- Pack de données intégré : planches (chêne, bouleau, épicéa), établi, dalles ; tags `planks`, `logs`
- Limites : pas de livre de recettes, pas de four (verre, pierre lisse), pas d'objets non-blocs

## ✅ M14 — Paysages variés
- Modèle `Landscape` inspiré de 1.18+ : continentalité (océans, côtes, terres), érosion (plaines plates
  « stables » ↔ montagnes à crêtes), plateaux à falaises, rivières creusées sous le niveau de la mer
  jusqu'aux océans, lacs de plaine avec rive garantie, ravins étroits et profonds
- Champs lents échantillonnés tous les 4 blocs puis interpolés : même hauteur en chunk ou en colonne isolée
- 18 biomes : océan (gelé), plage, rivière (gelée), lac, plaine, plaine sèche, plaine enneigée, forêt,
  forêt de bouleaux, forêt sombre (grands chênes), taïga, taïga enneigée, plateau, pics rocheux,
  pics enneigés, glacier (calotte de glace compacte) ; température abaissée par l'altitude
- Roche nue sur les pentes raides ; glace sur les eaux froides ; neige, glace, glace compacte ajoutées
- Répartition mesurée (graine 7, 6 × 6 km) : océans 28 %, plaines 22 %, forêts 30 %, rivières/lacs 8 %,
  plateaux 5 %, montagnes et glaciers 4 %, plages 3 %
- Point d'apparition cherché sur la terre ferme ; biome affiché dans F3
- Coût : génération + lumière ~0,75 ms/chunk (contre 0,65), relief plus riche (~189 quads/section)

## ✅ M15 — Cycle de l'eau et météo dynamique
- Modèle `Atmosphere` (grille de 48 × 48 cellules de 32 blocs autour du joueur) : température, vapeur,
  eau nuageuse et vent par cellule ; advection semi-lagrangienne ; saturation de Clausius-Clapeyron ;
  évaporation des mers et lacs (plus forte au chaud, au sec et au vent), transpiration des forêts
- Soulèvement orographique (pluie au vent des montagnes, ombre pluviométrique sous le vent), convection
  l'après-midi (averses, orages), dépressions et anticyclones qui dérivent (fronts, temps sec) ;
  gradient de 6,5 °C par 1000 blocs ; seuil de précipitation abaissé dans les nuages froids (Bergeron)
- Précipitations : pluie, neige fondue, neige, grêle (orages chauds) ; brouillard près de la saturation
  par vent faible, et dans les nuages au sommet des montagnes
- Rendu : nuages en blocs construits depuis le champ (base au niveau de condensation, épais et sombres
  en orage) qui glissent avec le vent ; pluie / neige / grêle autour de la caméra, arrêtées par les toits ;
  éclairs et flashs ; ciel gris, lumière baissée et brouillard rapproché par mauvais temps
- Effets au sol : la neige recouvre l'herbe, l'eau dormante gèle, l'une et l'autre fondent au dégel
- Hydrologie : sources de montagne coulant vers l'aval par le simulateur de fluides, rivières fines en
  amont et larges près des côtes, deltas à plusieurs bras et îlots
- F3 : bulletin météo (°C, humidité relative, nuages, base, précipitations, vent, hPa, brouillard, orage) ;
  `--weather rain|sleet|snow|hail|storm` fixe la météo pour les captures
- Ruisseaux pré-calculés à la génération (`Streams`) : depuis chaque source, descente selon la pente
  lissée de la carte des hauteurs, avec méandres, jusqu'à la mer, une rivière ou un creux ; lit de sources
  immobiles enfoncé d'un bloc, cascades remplies, berges : aucun débordement, rien à simuler ; tracé
  identique quel que soit le chunk qui le demande (continu aux frontières), mis en cache
- Fluides au chargement : écoulements interrompus (ticks non sauvegardés) et eau suspendue repris ;
  au démarrage, simulation accélérée (`fastForward`) avec barre de progression ; F3 : en attente, mises à jour/s

## ✅ M16 — Bassins versants et minimap
- `Hydrology` : des boulets d'eau partent de sources procédurales (plus nombreuses en altitude et sous
  climat humide) et dévalent le relief avec inertie ; rapides en pente, ils creusent et emportent des
  sédiments ; lents, ils les déposent (cônes, deltas) ; piégés dans un creux, ils le comblent jusqu'à
  déborder : aucun ne s'arrête avant la mer ou un lac
- Écoulement sur le relief creusé : remplissage des cuvettes depuis la mer (elles deviennent des lacs avec
  leur exutoire), débit = pluie cumulée vers l'aval ; les ruisseaux se rejoignent en rivières ; lit plus
  large et plus profond avec le débit, plus étroit en pente ; surface plate en travers, ne remontant
  jamais vers l'aval (gorges creusées à travers les bosses) ; berges relevées au niveau de l'eau
- Monde infini : tuiles de 1024 blocs sur une grille de 4 blocs, simulées avec une marge de 512 blocs,
  sources liées à la position (tuiles voisines cohérentes), cœur seul conservé en cache (~1 Mo/tuile) ;
  ~0,5 s par tuile sur les fils de chargement, puis génération inchangée (0,75 ms/chunk)
- Remplace les rivières au bruit, les deltas à bras et les ruisseaux tracés de la version précédente
- Minimap (touche M) : relief ombré, biomes, eaux (rivières colorées selon le débit), nuages et
  précipitations, température ; calculée en arrière-plan depuis le générateur (terres inexplorées
  comprises), recoloriée deux fois par seconde avec la météo ; `--minimap <couche>` pour les captures
- Affichage du creusement des tuiles en cours ; F3 : débit et surface de la rivière sous le joueur
