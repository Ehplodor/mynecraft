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
- Cohérence des masses d'eau : chaque cellule du lit prend le niveau du centre de rivière le plus
  proche (plus de marches là où les disques se chevauchent) ; l'eau gagne les creux voisins sous sa
  surface ; trous et îlots minces dans le lit inondés au lieu d'être murés (plus de piliers de berge)
- Rendu des fluides : hauteur de surface par coin (moyenne des cellules voisines, l'air libre comptant
  pour 0, comme vanilla) : paliers et bords en pentes continues ; les surfaces calmes restent plates et
  fusionnées ; la texture glisse dans le sens de la pente (courant) et descend le long des chutes
- Eau figée supprimée : les grottes ne percent plus le fond des rivières et des lacs ; les débordements
  vers le chunk voisin sont repérés dès la génération (colonnes du halo) ; seuls les creux peu profonds
  sont inondés, les ruptures de pente gardent une berge ; l'écoulement des terres chargées est
  pré-calculé sans délai (3 ms par image) : 400 chunks de rivières réglés en ~0,3 s de calcul

## ✅ M17 — Menu de redémarrage
- Échap ouvre un menu cliquable : reprendre, réapparaître au point de départ, recharger ce monde,
  nouveau monde (graine aléatoire, même graine, graine saisie : nombre ou texte haché comme vanilla),
  ouvrir un autre monde sauvegardé (clic droit pour passer au suivant), sauvegarder et quitter
- Les nouveaux mondes vont dans `monde-2`, `monde-3`… à côté du monde courant ; changer de monde
  sauvegarde puis relance le jeu avec les nouveaux arguments (mêmes options JVM, packs et réglages)
- Berges praticables : profil de vallée calculé par tuile (le sol près de l'eau ne dépasse pas le niveau
  de l'eau + 1, puis remonte en pente douce, plus douce le long des grandes rivières) ; mesuré sur
  1,4 × 1,4 km : 87 % des bords de rivière à 1 bloc ou moins au-dessus de l'eau (34 % avant)
- Sortie de l'eau : un nageur qui pousse contre un rebord est hissé dessus (comme vanilla) ; la poussée
  dure tant que l'eau est au contact du corps ou juste sous les pieds, soit un rebord d'un bloc environ
- Plus de murs de roche artificiels : la roche nue des pentes raides se juge contre la surface de l'eau
  voisine (pas contre le fond du lit) ; les sédiments déposés en mer ou dans un lac restent sous l'eau
  (hauts-fonds) ; une berge n'est jamais plus basse que l'eau qui la touche (rivières perchées) ; une
  berge relevée redescend vers l'extérieur en levée d'un bloc par colonne sur 6 colonnes (marge de
  8 colonnes calculée seulement près d'une rivière : génération inchangée, 0,75 ms/chunk)
- Mesures (montagnes, graine 7) : roche nue près de l'eau 6,6 % → 1,6 % ; berges à 1 bloc ou moins 91 %
- L'eau naturelle ne déborde plus de ses paliers (quantité finie) : cascades dessinées dans le lit
- Eau suspendue et écoulements : l'eau en mouvement posée sur de l'eau ne s'étale plus dessus (comme
  vanilla) : une chute rejoint le bassin, un palier de rivière déborde en cascade dans le tronçon
  suivant ; les paliers sont donc de nouveau simulés (et pré-calculés) au lieu d'être figés ; les ravins
  ne tranchent plus le flanc d'un lit, les grottes restent sous le fond du lit voisin le plus profond ;
  un creux n'est inondé que s'il est fermé de tous côtés. Zone de test en montagne : écoulements hors
  des lits 3 843 → 0 blocs
- Rivières à grande échelle : le réseau (débit, lits, lacs, berges) est calculé sur le relief de base,
  identique d'une tuile à l'autre ; l'érosion des boulets ne sculpte que les versants, fondue entre
  tuiles sur 128 blocs et atténuée près de l'eau ; un chenal ne monte jamais sur ses propres dépôts ;
  les berges suivent l'eau la plus proche ; les ravins s'effacent près de l'eau. Mesures : rivières
  coupées aux frontières de tuiles 70 % → 33 % ; rivières perchées de 5+ blocs 10,3 % → 0,3 %
  (graine de test de l'utilisateur) et 7,3 % → 0,7 % (graine 7). Coût : ~1,4 s par tuile de 1 km
  (calcul unique, en arrière-plan), génération ~1 ms/chunk

## ✅ M18 — Carte du monde, repères, exploration, sons
- Carte du monde plein écran (J) : zoom 1 à 16 blocs/pixel autour du curseur, glisser pour se déplacer,
  couches (M), recentrage (C), coordonnées / hauteur / biome sous le curseur, échelle ; image calculée
  en arrière-plan pour la vue, l'ancienne affichée déplacée et mise à l'échelle en attendant
- Mémoire de l'exploré : chunks vus enregistrés (`explored.bin`) ; l'inexploré est grisé sur les deux cartes
- Repères : B (ici) ou clic droit sur la carte (clic droit dessus : retiré), noms et couleurs, sauvegardés
  (`waypoints.json`), affichés sur les cartes (épinglés au bord de la minimap) et dans le monde
  (nom et distance)
- Sons (OpenAL, synthétisés, aucun fichier) : pluie (étouffée sous un toit), vent (plus fort en altitude),
  rivières et chutes placées en 3D au plus près, tonnerre retardé selon la distance (343 m/s), pas selon
  le sol (herbe, pierre, sable, bois, neige, eau), blocs cassés et posés ; silencieux sans périphérique audio
- Touches selon la disposition réelle du clavier (M, J, B, C justes en AZERTY) ; minimap affichée par défaut

## ✅ M18 bis — Bruitages
- Synthèse physique (`client/sound/Synth`, `Dsp`), aucun fichier audio : impacts = transitoire + modes
  amortis ; bulles = résonance de Minnaert (sinus qui monte et s'éteint) ; frottements et craquements
  = nuages de micro-grains filtrés ; boucles rendues plus longues puis fondues sur elles-mêmes, modulations
  lentes en nombre entier de cycles ; graves gardés dans la bande audible des petits haut-parleurs
- Ambiances : pluie légère / forte / sur un toit, grêle, vent à rafales et sifflement, feuillage en forêt,
  ruisseau (bulles), rivière (grondement), chute d'eau, ressac sur le rivage le plus proche, grillons la
  nuit en prairie, grotte (bourdonnement, gouttes avec échos), oiseaux le jour (6 chants), tonnerre
  proche (claquement, déchirure, roulement) ou lointain, retardé selon la distance
- Pas (herbe, terre, sable, gravier, pierre, bois, neige, eau, feuilles, verre) : talon puis pointe,
  4 variantes ; blocs : coups de minage, casse (débris qui rebondissent, bois qui se fend, éclats de verre,
  terre qui s'effrite...), pose ; plongeon ; clic d'interface
- Mixage : niveaux lissés selon lieu, heure, météo ; réverbération selon l'endroit (plein air, forêt,
  abri, grotte) et sons extérieurs étouffés sous un toit avec EFX ; synthèse en arrière-plan au démarrage
- Vérifié hors jeu : spectres, raccords de boucle, fins sans clic (test `SynthTest`) ; planches d'écoute
- Seconde passe après écoute (« artificiel, grésille, trop fort ») : chaque son réglé sur une sonie cible
  par nature (pas −36 dB, casse −30, tonnerre proche −24...) au lieu de sa crête (crêtes 0,04-0,47 au lieu
  de 0,8-0,97) ; plus de saturation ; grains à enveloppe en cosinus (plus d'attaques instantanées), contenu
  surtout entre 0,5 et 5 kHz (énergie > 6 kHz : 11 % au plus) ; premières réflexions sur les sons ponctuels ;
  ruisseau en résonances mouvantes plutôt qu'en nuée de bulles ; chants d'oiseaux avec micro-instabilités ;
  réglage du volume dans le menu pause (60 % par défaut, `options.properties` partagé par les mondes)

## ✅ M19 — Visionneuse de recettes et confort d'inventaire
- Visionneuse façon JEI à droite de l'inventaire : tous les objets, recherche par nom ou identifiant (sans
  tenir compte des accents ni de la casse), pages (molette) ; clic ou R : recettes de l'objet, clic droit
  ou U : ses utilisations (R / U marchent aussi sur les objets de l'inventaire) ; panneau de recette à
  gauche : grille avec les alternatives d'un ingrédient qui défilent, résultat, « Fabricable xN » compté
  sur l'inventaire et la grille, « Établi requis » pour les recettes 3x3 dans l'inventaire, bouton « + »
  qui pose les ingrédients dans la grille (Maj : autant que possible) ; clic sur un ingrédient : ses recettes
- Confort : T trie l'inventaire principal (piles fusionnées, par bloc), 1-9 au-dessus d'une case l'échange
  avec la barre, double-clic regroupe le même objet sur le curseur, glisser une pile tenue la répartit
  également (clic droit : une par case)
- Noms affichés depuis un fichier de langue (`lang/fr_fr.json` du pack par défaut, remplaçable par un
  resource pack), sinon tirés de l'identifiant ; recettes ajoutées : glace compactée, andésite
- Logique testée sans écran (`RecipeIndex`, `CraftingMenu`)

## ✅ M20 — Ombres solaires, reflets de l'eau, textures animées
- Carte d'ombres du soleil : 2048², rayon 96 blocs, orthographique, calée sur les texels (pas de scintillement), filtrage PCF matériel 4 prises, décalage selon la normale ; s'efface la nuit et par temps couvert.
- Monde rendu hors écran (`SceneTarget`) et copié avant la passe translucide : l'eau réfracte le fond (déformé par les vaguelettes), s'assombrit avec l'épaisseur, reflète le ciel selon Fresnel, et le soleil y scintille.
- Textures animées depuis `.png.mcmeta` (frametime, frames, interpolate) : seule la couche qui change est renvoyée au GPU. Eau et lave du pack par défaut animées.

## ✅ M22 — Animaux et créatures
Dix espèces vivent autour du joueur (`world/life`), chacune avec un habitat (biomes, climat), un régime, un rythme (jour, nuit, crépuscule), un tempérament et un modèle animé en boîtes :
- **Réelles** : cerf (hardes, broute, fuit), lapin (bondit, pelage blanc dans la neige), renard (chasse les lapins la nuit, pelage blanc en zone enneigée), truite (bancs, fuit, s'agite hors de l'eau), héron cendré (pêche immobile dans les hauts-fonds, s'envole quand on approche), bouquetin (pics et plateaux).
- **Fantastiques, originales** : *Lanterne des brumes* (cloche lumineuse dérivant la nuit au-dessus de l'eau, tentacules ondulants), *Moussard* (tortue géante des forêts portant un arbre et des champignons lumineux, curieuse, rentre dans sa carapace si on la frappe), *Givrelin* (petite créature de glace vivante qui bondit et tourne autour du joueur), *Voilier des cimes* (raie géante planant en grands cercles au-dessus des sommets, nervures lumineuses).
- Comportements : errance, repos, sommeil hors de leurs heures, broutage (tête baissée, faim qui diminue), chasse et prédation (renard → lapin, héron → truite), fuite (distance réduite de moitié en se baissant), cohésion de harde, petits qui suivent un adulte, évitement de l'eau, des falaises et des murs, saut des marches.
- Population : chaque lieu nourrit un nombre d'animaux par espèce (`Ecosystem.capacity` : nourriture du biome × densité de l'espèce, proies plus nombreuses que prédateurs) ; les nouveaux arrivants comblent ce qui manque ; les adultes nourris se reproduisent si le lieu peut nourrir un petit de plus ; les petits grandissent.
- Animation : marche diagonale des quadrupèdes, bonds, têtes qui regardent le joueur, battements d'ailes (repliées au sol), nageoires, queues, oreilles qui frémissent, tentacules, inclinaison en virage, couché pour dormir, héron sur une patte, éclair rouge quand on frappe, chute sur le flanc à la mort. Éclairés comme les blocs et projetant des ombres.
- Joueur : viser un animal affiche son nom ; clic gauche le frappe (recul, fuite).
- `--showcase` place une espèce de chaque devant la caméra ; `--pos x,~,z` démarre au sol.
- Correctif M20 : la carte d'ombres n'était pas liée à son unité de texture ; les ombres sont maintenant réellement visibles.

### Préparé pour M23 (saisons et écosystème vivant)
- `Ecosystem` centralise tout ce qui dépendra de la saison : `Season` (nom, phase, végétation, période de reproduction, limite des neiges), `seasonAt(jour)` donne déjà une année de 32 jours en quatre saisons, `setTurning(true)` suffira à la faire tourner (fixée à la fin du printemps pour l'instant).
- Nourriture (`forage`), capacité d'accueil, faim, reproduction et pelage blanc lisent déjà la saison : l'hiver réduira la nourriture, donc les populations ; les pelages blanchiront avec la neige.
- Reste à faire en M23 : sauvegarde des animaux, migrations saisonnières, hibernation, végétation qui pousse/jaunit, chaîne alimentaire avec effet sur la végétation, butin.

## ✅ M23 — Saisons et écosystème vivant
**Saisons** : l'année compte 32 jours (8 par saison) et suit l'horloge du monde (désormais sauvegardée en entier). La température de l'air varie de +7 °C au cœur de l'été à −11 °C au cœur de l'hiver ; la météo en tient compte (neige l'hiver). Les teintes changent : herbe fraîche au printemps, herbe sèche, feuilles rouges, orange et jaunes en automne (selon l'arbre), bois nus en hiver (la taïga garde ses aiguilles), neige partout où il gèle. Les couleurs sont redessinées progressivement, quelques chunks par image.

**Écologie des communautés** (`world/life/Ecology`) : le monde est découpé en parcelles de 64 × 64 blocs, chacune avec un petit modèle de communauté intégré jour après jour, y compris loin du joueur :
- *Producteurs* : biomasse végétale logistique vers ce que permettent le biome, la pluie, la saison et la température, plus rapide sur sol riche et là où travaillent les pollinisateurs ; fleurs pendant la saison chaude ; vie aquatique (algues, larves) nourrie par les nutriments lessivés depuis les terres.
- *Consommateurs* : naissances pendant la saison de reproduction propre à chaque espèce (lapins au printemps, truites en automne, givrelins en plein hiver...), freinées par l'encombrement (compétition intraspécifique, croissance logistique), mortalité accrue quand la nourriture manque, réponse fonctionnelle de type II (saturante).
- *Interactions* (cf. « Biological interaction ») : **prédation** (renard → lapin, héron → truite) ; **herbivorie** et la **compétition par exploitation** qu'elle crée entre brouteurs qui partagent l'herbe ; **mutualisme** (lanternes des brumes ↔ fleurs : pollinisation contre nectar) ; **commensalisme** (le givrelin se nourrit du lichen dégagé par les sabots des cerfs et bouquetins, sans effet pour eux) ; **parasitisme** (une maladie qui se propage avec la densité des hardes, rend les animaux plus lents et ternit leur pelage) ; **décomposition** (les champignons du moussard accélèrent le recyclage).
- *Cycle des nutriments* : cadavres et déjections → détritus → sol (décomposition ralentie par le froid) → plantes ; lessivage vers l'eau ; les voiliers des cimes fertilisent les sommets.
- *Espace et saisons* : dispersion entre parcelles voisines (vers les moins encombrées), migration des hérons et des voiliers (départ l'hiver, retour au printemps), hibernation des moussards et des lanternes, estivation des givrelins.
- *Amorçage* : chaque nouvelle parcelle est ensemencée sobrement puis simulée 6 ans avant d'être vue : la communauté a déjà trouvé son équilibre ou ses cycles. L'état est sauvegardé dans `ecology.bin`.
- *Lien avec le visible* : les animaux qu'on rencontre sont tirés des effectifs de la parcelle ; ceux qui meurent (proies, chasse) sont retirés de la parcelle et nourrissent les détritus ; les brouteurs mangent réellement les touffes d'herbe ; l'herbe et les coquelicots poussent ou fanent autour du joueur selon la végétation et les fleurs de la parcelle et la saison.
- **Carnet du naturaliste** (`N`) : saison, jour, parcelle, jauges (végétation, fleurs, sol, détritus, vie aquatique), effectifs avec tendance, endormis, malades, et interactions à l'œuvre classées par intensité.
- Tests : coexistence proies-prédateurs bornée sur 10 ans, contrôle descendant par les prédateurs (et cascade sur les plantes), saisons des plantes, migration et hibernation, bénéfice mutuel lanternes-fleurs, cadavres → sol, épidémie dans une harde trop dense, sauvegarde.

Pistes ensuite : gel des lacs en hiver et couche de neige posée au sol, chaînes plus longues (loups, insectes visibles), maladies transmises entre espèces, succession végétale (prairie → forêt), influence du joueur (chasse, cultures) mesurée dans le carnet.

## ✅ M24 — Calendrier local des plantes et gel
**Végétation** (`Vegetation`) : chaque colonne a son propre calendrier, lu dans sa température (climat de la colonne à son altitude + variation saisonnière) plutôt que dans la date seule. La saison de croissance est la partie de l'année au-dessus de 5 °C : un fond de vallée tempéré pousse du début du printemps au début de l'automne, un alpage seulement quelques jours autour du cœur de l'été. Les couleurs de l'herbe et des feuilles suivent ce calendrier local (printemps tardif et automne précoce en altitude), et une plaine chaude et sèche jaunit en plein été (sécheresse). Les touffes d'herbe et les coquelicots poussent selon la croissance locale, les fleurs s'ouvrent aussi longtemps après le réveil des plantes qu'en plaine (donc plus tard sur les hauteurs). Le carnet affiche la pousse, la floraison et la sécheresse là où l'on se tient.

**Gel** (`Frost`) : indépendamment de la météo du moment, la température moyenne de la saison fait geler l'eau dormante en hiver, depuis les rives vers le large (la glace s'étend depuis la glace déjà prise), et la rouvre au printemps par les bords ; une forte gelée (sous −2 °C) tue les touffes d'herbe et les fleurs.

Reste à faire, repris en M25 et M26 : vérification visuelle en jeu, couche de neige posée au sol, écologie branchée sur le calendrier local, fleurs alpines, épaisseur de la glace.

---

# Vision : vivre en harmonie avec soi-même et avec la nature

Le défi du jeu n'est ni de survivre à des monstres ni de produire le plus possible : c'est de **mener
une vie complète (se nourrir, s'abriter, fabriquer, créer, échanger, apprendre) sans épuiser le monde
qui la rend possible, et en y trouvant son équilibre**. Cela demande beaucoup de métiers : architecture,
agriculture, industrie, chasse, pêche, cueillette, art, énergie et flux de matières, commerce,
construction, fabrication, cuisine, outils, recettes, plans, biodiversité animale et végétale, rôle et
compétences.

## Ce qui est déjà là
Le monde vit sans le joueur : eau, météo, bassins versants, saisons, calendrier local des plantes, gel,
écologie des communautés (producteurs, consommateurs, prédation, mutualisme, cycle des nutriments).
C'est exactement le terrain dont le défi a besoin : on peut **mesurer** l'effet du joueur sur la nature.

## Le risque : vingt jeux juxtaposés
Avec autant de métiers, le danger est d'empiler des mini-jeux qui ne se parlent pas. Les jeux qui
réussissent cette ampleur (Dwarf Fortress, Valheim, Stardew Valley, RimWorld, Vintage Story) partagent
quelques systèmes communs que chaque métier réutilise. Cinq règles pour tout ce qui suit :

1. **Tout vient de quelque part et retourne quelque part (flux de matières).** Chaque objet a une
   origine dans la simulation (une parcelle, une espèce, un filon, une rivière) et une fin (consommé,
   usé, composté, jeté). La viande vient des effectifs de la parcelle, le bois de ses arbres, les
   déchets d'une forge vont dans l'eau ou le sol. Aucun objet ne sort de nulle part.
2. **Une boussole d'harmonie.** Deux mesures suivies en continu et affichées dans le carnet :
   *harmonie avec la nature* (biodiversité, sol, eau, populations du territoire du joueur comparées
   à leur état d'équilibre) et *harmonie avec soi* (besoins satisfaits, variété des repas, confort
   de l'abri, beauté du lieu, activités variées, liens avec les autres). Chaque fonctionnalité doit
   faire bouger au moins l'une des deux.
3. **Une seule grammaire de fabrication.** Établi, cuisine, forge, atelier d'art et machines utilisent
   le même modèle : recette = entrées, outil ou poste, savoir-faire requis, durée, énergie, sorties et
   déchets. Les data packs l'étendent ; l'API de mods aussi.
4. **Apprendre en faisant.** Chaque métier a sa compétence, qui progresse par la pratique et débloque
   recettes, plans et gestes plus fins. Le rôle du personnage naît de ce qu'il pratique, pas d'une classe
   choisie au départ.
5. **Les PNJ vivent selon les mêmes règles que le joueur.** Ils ont les mêmes besoins, compétences,
   recettes et flux de matières : un village qui chasse trop vide la forêt comme le ferait le joueur,
   un forgeron a besoin de charbon. Leurs vies variées naissent de ces systèmes, pas de scripts.

## Métiers et capacités
Un système unique, partagé par le joueur et les PNJ, que chaque étape enrichit :
- **Capacités** (ce que le corps et l'esprit savent faire) : force, endurance, adresse, perception,
  patience, mémoire des lieux, sens esthétique. Elles progressent lentement par l'usage, baissent avec
  la fatigue, la faim ou le froid, et donnent la qualité des gestes (précision du tir, vitesse de coupe,
  finesse d'une poterie).
- **Compétences** (savoir-faire d'un domaine, en niveaux) : chacune progresse par la pratique et
  débloque recettes, plans, outils et gestes. Ce qu'on n'exerce plus s'émousse doucement.
- **Métiers** (combinaisons de compétences reconnues par les autres) : paysan, jardinier, éleveur,
  apiculteur, bûcheron, charpentier, maçon, architecte, chasseur, pêcheur, cueilleur, herboriste,
  guérisseur, cuisinier, meunier, boulanger, forgeron, fondeur, verrier, potier, tisserand, teinturier,
  tanneur, ingénieur, artiste, musicien, conteur, marchand. Un métier donne un rôle dans le hameau,
  des commandes et des titres (apprenti, compagnon, maître).
- **Transmission** : un maître enseigne plus vite qu'on n'apprend seul ; le joueur peut être l'apprenti
  d'un PNJ ou prendre lui-même des apprentis. Livres, plans et carnets conservent les savoirs.
- **Spécialisation sans enfermement** : on peut tout essayer, mais l'excellence dans un métier demande
  du temps ; un personnage complet est un choix, pas une obligation.

Mesure de l'expérience : à chaque étape, une partie de 20 minutes avec trois questions (qu'ai-je eu
envie de faire ? qu'est-ce qui m'a frustré ? quel geste m'a semblé juste envers le monde ?).

---

# À venir

## Pourquoi cet ordre
Les étapes suivent les dépendances entre systèmes, en quatre phases :
1. **Fondations (M25 à M28)** : ce que tous les métiers partagent. Identifiants, objets, grammaire de
   fabrication, flux de matières, capacités et compétences, besoins et boussole. Sans elles, chaque
   métier réinventerait ses propres règles (le risque des « vingt jeux juxtaposés »).
2. **Vivre de la nature (M29 à M31)** : la première boucle complète (besoins → prélever dans la nature
   → cuisiner → s'abriter → mesurer son empreinte) d'abord seul, puis avec les premiers habitants.
   Les PNJ arrivent dès que leurs maisons peuvent être bâties, pour que chaque métier suivant soit
   conçu d'emblée pour eux et pour le joueur.
3. **Transformer le monde (M32 à M34)** : cultiver, produire de l'énergie, transformer les matières,
   créer. Chaque étape ajoute ses métiers au joueur *et* aux PNJ, et ses effets à l'écologie.
4. **Une société vivante (M35 à M37)** : vies variées, familles, commerce, rôles ; biodiversité élargie ;
   ouverture aux mods une fois tous les registres éprouvés par les étapes précédentes.

Fils continus à chaque étape : les espèces animales et végétales dont l'étape a besoin (abeilles pour
l'élevage, plantes tinctoriales pour l'art…), les recettes du pack de données, une partie de test de
20 minutes, et la CI au vert.

## Phase 1 — Fondations

### M25 — Socle technique
- CI GitHub Actions : compilation et tests à chaque PR
- Lever le plafond de 127 états de bloc (identifiants 16 bits ou palette par section)
- **Registre d'objets séparé des blocs** (outils, nourriture, matériaux, objets d'art)
- Registres pilotés par les données (blocs, objets, espèces) prêts pour les data packs puis l'API de mods
- Captures scriptées rejouées en CI (comparaison d'images, vérification visuelle de M24)

### M26 — Finir l'hiver
- Couche de neige au sol, glace d'épaisseur variable (on marche dessus, on la brise), écologie branchée
  sur le calendrier local, fleurs alpines, traces dans la neige
- Clôt le travail en cours sur les saisons avant que les besoins du corps (froid) n'en dépendent

### M27 — Grammaire commune : fabriquer, prélever, apprendre
- **Grammaire de fabrication** : recette = entrées, poste, outil, compétence requise, durée, énergie,
  sorties et déchets ; établi et fabrication actuels migrés dessus ; format data pack étendu
- **Flux de matières** : chaque prélèvement est retiré d'une parcelle de l'écologie (plantes, animaux,
  sol, eau, filons) et chaque déchet y retourne ; un registre des flux par parcelle alimente le carnet
- **Outils** : objets avec matériau, qualité, usure, réparation ; le minage à la main de M12 devient
  un cas particulier
- **Métiers et capacités** : capacités, compétences par la pratique, niveaux, déblocages, titres ;
  appliqués d'abord aux gestes existants (miner, bâtir, fabriquer)
- Tout ce système est écrit pour un « acteur » (joueur ou PNJ), pas pour le joueur seul

### M28 — Le corps et la boussole
- Besoins : faim, soif, fatigue, chaleur (lit l'hiver de M26) ; repas plus nourrissants quand ils sont variés
- Bien-être : abri, confort, beauté du lieu, repos, variété des activités
- **Boussole d'harmonie** dans le carnet : nature (biodiversité, sol, eau, populations du territoire,
  lus dans le registre des flux) et soi (besoins, bien-être)
- Territoire défini autour du foyer ; rythmes de jeu réglables (besoins lents ou désactivés)

## Phase 2 — Vivre de la nature

### M29 — Cueillir, pêcher, chasser, cuisiner
- Cueillette : baies, champignons, herbes, fruits, graines, selon saison et calendrier local ; ce qui
  est cueilli manque à la parcelle et repousse
- Pêche : canne, nasses ; poissons tirés des effectifs de la parcelle
- Chasse : arc, pièges ; gibier tiré des effectifs, surchasse visible, respect des saisons de
  reproduction récompensé par la boussole
- Cuisine : feu de camp, recettes simples, conservation (séchage, fumage, sel)
- Métiers : cueilleur, herboriste, pêcheur, chasseur, cuisinier
- **Première boucle jouable complète** : se nourrir de la nature sans l'épuiser

### M30 — Bâtir
- Formes : escaliers, barrières, murets, portes, fenêtres, toits, poutres, `uvlock`, modèles non cubiques
- Architecture qui compte : isolation (chaleur), lumière, toit contre la pluie, cave fraîche pour les
  réserves, beauté du lieu dans le bien-être
- **Plans et blueprints** : tracer un bâtiment, liste des matériaux, construction pas à pas ; un plan
  est un objet qu'on peut copier, échanger, recevoir d'un maître ; confort en créatif (annuler,
  remplissage, symétrie)
- Métiers : bâtisseur, charpentier, maçon, architecte
- Prérequis des hameaux : les PNJ bâtiront avec les mêmes plans

### M31 — Premiers habitants
- PNJ acteurs : mêmes besoins, capacités, compétences, outils, recettes et flux que le joueur
- Déplacement dans le monde voxel (recherche de chemin, portes, escaliers, eau), maison, emploi du
  temps selon le jour et la saison, abri de la pluie, feu l'hiver
- Premiers métiers exercés : cueilleur, pêcheur, chasseur, cuisinier, bâtisseur ; ils construisent
  avec les plans de M30
- Un ermite, une famille, un petit hameau ; leur empreinte s'inscrit dans le registre des flux
- **Transmission** : un PNJ maître enseigne un savoir-faire ou un plan au joueur, et l'inverse
- Dialogues simples (besoins, conseils, demandes) ; harmonie avec les autres dans la boussole

## Phase 3 — Transformer le monde

### M32 — Cultiver et élever
- Cultures à plusieurs âges selon sol, humidité et calendrier local ; semences sauvages domestiquées
- Arrosage et irrigation par l'eau du simulateur ; compost et fumure (cycle des nutriments)
- Vergers, haies, plantation d'arbres, succession végétale ; associations de cultures et jachère
- Élevage doux (poules, chèvres, moutons, abeilles) qui puise et rend à la parcelle
- Métiers : paysan, jardinier, éleveur, apiculteur, berger ; champs des hameaux cultivés par les PNJ

### M33 — Énergie et flux de matières
- Énergie : roue à aubes au débit réel de la rivière, moulin à vent selon le vent de l'atmosphère,
  force animale, bois de chauffe (forêt qui recule si on abat trop)
- Transformations : meule, four, boulangerie, scierie, forge, fonderie, tannerie, verrerie
- Flux visibles : scories, cendres, eaux usées retournent au sol ou à la rivière et l'écologie en
  tient compte (une tannerie en amont trouble les truites)
- Logistique douce : canaux, rigoles, goulottes, charrettes
- Métiers : meunier, boulanger, bûcheron, forgeron, fondeur, verrier, tanneur, ingénieur

### M34 — Créer
- Teintures tirées des plantes et des minéraux, peinture, sculpture, poterie, tissage, instruments
  et musique jouée ; décoration qui nourrit le bien-être et la beauté du lieu
- Métiers : potier, tisserand, teinturier, artiste, musicien ; œuvres des PNJ dans les hameaux

## Phase 4 — Une société vivante

### M35 — Les gens du pays (vies variées et commerce)
- Vies variées : paysans, artisans, bergers transhumants, marchands itinérants, herboristes, ermites,
  artistes, nomades saisonniers
- Chacun a un âge, une famille, un caractère, des goûts, des aspirations et une histoire qui se
  déroule : il naît, apprend un métier auprès d'un maître, se marie ou non, déménage, vieillit,
  transmet son savoir
- Calendrier social : semailles, moissons, foires, veillées d'hiver, fêtes du printemps
- Relations entre eux (amitiés, rivalités, entraide) et avec le joueur (confiance, services rendus)
- Hameaux qui naissent, grandissent ou déclinent selon les ressources de leur territoire
- Commerce : troc puis monnaie, prix selon rareté et saison, commandes, foires
- Rôle du joueur : sa place (paysan, artisan, marchand, ermite, guérisseur…) naît de ce qu'il
  pratique et de ce que le hameau attend de lui
- Métiers : marchand, conteur, guérisseur ; titres d'apprenti, compagnon, maître reconnus par le hameau

### M36 — Biodiversité élargie
- Nouvelles espèces animales (loups, insectes visibles, oiseaux, amphibiens) et végétales (arbres,
  fleurs, champignons, plantes médicinales) ; chaînes alimentaires plus longues
- Maladies entre espèces, espèces sensibles à la pollution, réintroduction par le joueur ou les PNJ

### M37 — API de mods
- Ouverture des registres, de la grammaire de fabrication, des métiers et des comportements de PNJ
  aux JAR externes ; événements (récolte, prise de gibier, saison, recette apprise, PNJ qui change
  de métier…), inspirée de Fabric API comme le prévoit l'étude de faisabilité
- Un mod d'exemple (une espèce, une culture, un métier) et sa documentation

## Pistes ensuite
- Courant qui pousse le joueur, lave qui brûle, surfaces inclinées
- Mode photo, lieux remarquables, musique générative
- Multijoueur coopératif (un hameau à plusieurs)
