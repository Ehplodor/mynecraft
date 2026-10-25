# Mynecraft

Moteur voxel open source inspiré de Minecraft Java Edition, écrit de zéro (aucun code ni asset Mojang).
Voir l'[étude de faisabilité](docs/ETUDE_FAISABILITE.md) et la [feuille de route](docs/ROADMAP.md).

**Principe directeur : performance maximale.**

## Prérequis
- JDK 21, GPU OpenGL 3.3+ (Gradle est fourni par l'environnement ; `gradle wrapper` pour générer le wrapper)

## Lancer
```bash
gradle run                                   # jeu
gradle run --args="--no-vsync"               # FPS non plafonnés (affichés dans le titre)
gradle run --args="--screenshot shot.png"    # rend une image, affiche des stats, quitte
                                             #   (--view lacet,tangage en degrés, --pos x,y,z, --time ticks,
                                             #    --no-occlusion, --fullbright, --weather rain/snow/hail/storm)
gradle run --args="--pack MonPack.zip"       # resource pack Java (zip ou dossier), répétable
gradle run --args="--datapack MonData.zip"   # data pack (recettes, tags) ; aussi lus depuis <monde>/datapacks/
gradle run --args="--world saves/test --seed 42"   # dossier du monde (défaut saves/world), seed d'un nouveau monde
gradle run --args="--benchmark 20"           # vol scripté 20 s : FPS, percentiles, temps GPU, mémoire
gradle test                                  # tests + micro-benchmark génération/lumière/maillage
```

Modes : **survie** par défaut (vie, chutes, noyade, lave, minage chronométré, inventaire), **créatif** avec
`F4` ou `--creative` (blocs illimités, vol avec `F`). En survie, la molette change d'emplacement ; à la mort, `R`
fait réapparaître au point d'apparition.

`E` ouvre l'inventaire (grille 2×2) ; clic droit sur un établi : grille 3×3 (Maj pour poser contre lui). En créatif,
`E` ouvre la palette de tous les blocs.

Contrôles : `F3` écran de débogage (FPS, temps GPU, sections, mémoire…), `Échap` pause (`Q` y quitte), souris pour regarder, `ZQSD`/`WASD` (position physique des touches), `Espace` sauter,
`F` mode vol (`Espace`/`Maj` monter/descendre), `Ctrl` sprint, clic gauche casser, clic droit poser,
`1`–`9` choisir l'emplacement de la barre, molette changer son bloc (dalles et bûches s'orientent selon la face visée).

## Architecture
| Fichier | Rôle |
|---|---|
| `world/Section`, `Chunk`, `World` | Stockage : 1 octet/bloc, sections 16³ vides = `null` |
| `world/TerrainGenerator`, `Noise` | Relief fBm + chaînes de montagnes, grottes (bruit 3D interpolé), filons, arbres ; déterministe par seed |
| `world/weather/Atmosphere`, `Weather` | Météo dynamique : masses d'air, vent, pressions, nuages, pluie/neige/grêle, brouillard ; neige et gel au sol |
| `client/WeatherRenderer` | Nuages en blocs depuis le champ nuageux, précipitations abritées par les toits, éclairs |
| `world/Player`, `Raycast` | Physique AABB par balayage d'axe sur les vraies boîtes (dalles), montée de marche 0,6, raycast DDA |
| `world/ChunkStreamer` | Monde infini : chargement du plus proche au plus loin, hystérésis, déchargement + sauvegarde |
| `world/storage/*` | NBT, fichiers région Anvil `.mca`, codec de chunk 1.16+/1.18+, `level.dat` |
| `world/Blocks` | Registre d'**états** (un id par état : `oak_log[axis=x]`, `oak_slab[type=top]`…) : rendu, forme, boîte de collision, opacité, émission, teinte |
| `world/FluidSimulator` | Eau et lave qui coulent (ticks programmés, niveaux vanilla, sources infinies, obsidienne/pierre) |
| `world/Survival`, `Inventory` | Vie, chutes, lave, noyade, régénération ; 36 emplacements de 64 ; butins vanilla |
| `world/storage/PlayerData` | Joueur dans `level.dat` avec les clés vanilla (`Health`, `Air`, `Inventory`, `playerGameType`) |
| `world/crafting/RecipeBook`, `CraftingMenu` | Recettes de data packs (formes, sans forme, tags, formats 1.21 et anciens) ; règles de clic vanilla |
| `client/InventoryScreen` | Écrans inventaire / établi / palette créative |
| `world/Placement` | Règles de pose vanilla (axe des bûches, demi-dalles, fusion en dalle double) |
| `world/LightEngine` | Lumière ciel + blocs (BFS ajout/retrait incrémental, traverse les chunks) |
| `world/ChunkMesher` | **Greedy meshing** (fusion à lumière et AO égales), lumière lisse + AO par sommet, eau abaissée, plantes en croix, **16 o/quad** (vertex pulling) |
| `resource/PackSource`, `ResourceManager` | Packs dossier/zip/classpath empilés (le dernier gagne) |
| `resource/BlockAppearance` | `blockstates` → `models` (héritage `parent`, refs `#`) → textures + `tintindex` |
| `client/TextureArray` | Toutes les textures dans un `GL_TEXTURE_2D_ARRAY` (mipmaps, répétition sur quads fusionnés) |
| `world/SectionVisibility`, `OcclusionCuller` | Connexions entre faces de chaque section, parcours de visibilité par image (occlusion culling) |
| `world/QuadAllocator` | Allocateur first-fit avec fusion des blocs libres, pour le tampon GPU partagé |
| `client/WorldRenderer` | Un seul VBO pour tout le monde, **1 appel de dessin par passe** (`glMultiDrawElementsBaseVertex`), 3 passes triées, remaillage incrémental versionné |
| `client/SkyRenderer` | Soleil, phases de lune, nuages (textures `environment/` du pack) |
| `client/DayCycle`, `BiomeMap` | Cycle jour/nuit (`DayTime` vanilla), couleurs de biome depuis les colormaps du pack |
| `client/Hud` | Couche 2D immédiate : barre d'objets (icônes isométriques), texte (police du pack), rectangles — un seul appel de dessin |
| `resource/FontLoader` | Police bitmap vanilla (`font/default.json` + `ascii.png`, largeurs mesurées) |
| `client/Overlay` | Réticule et contour du bloc visé |

## Resource packs
Format Java Edition (`pack.mcmeta`, `assets/<ns>/blockstates|models|textures`). Le pack intégré
(`src/main/resources/defaultpack`) est une création originale, régénérable avec `python3 tools/gen_default_pack.py`.
Supporté : variantes (première, listes pondérées), `multipart` (première partie), parents, références `#`,
`tintindex` (couleur fixe en attendant les biomes), textures de taille quelconque (mise à l'échelle commune),
textures animées (première image). Non supporté pour l'instant : formes non cubiques, rotations, `.mcmeta` d'animation.

## Mondes et sauvegarde
Format Java Edition 1.18+ (hauteur -64..319) : `level.dat` + `region/r.X.Z.mca` (Anvil, zlib). Sauvegarde automatique toutes les 30 s
et à la fermeture ; seuls les chunks **modifiés** sont écrits (la génération est déterministe).
Un monde **vanilla** peut être ouvert (`--world chemin/vers/monde`) : il est en **lecture seule** par défaut,
car Mynecraft ne représente pas encore tout (entités, propriétés de blocs ; les blocs inconnus sont approximés). `--allow-foreign-writes` force l'écriture, à vos risques.
