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
gradle run --args="--screenshot shot.png"    # rend une image puis quitte (avec --view lacet,tangage en degrés)
gradle run --args="--pack MonPack.zip"       # resource pack Java (zip ou dossier), répétable
gradle run --args="--world saves/test --seed 42"   # dossier du monde (défaut saves/world), seed d'un nouveau monde
gradle test                                  # tests + micro-benchmark génération/maillage
```

Contrôles : souris pour regarder, `ZQSD`/`WASD` (position physique des touches), `Espace` sauter,
`F` mode vol (`Espace`/`Maj` monter/descendre), `Ctrl` sprint, clic gauche casser, clic droit poser,
`1`–`9` choisir le bloc, `Échap` quitter.

## Architecture
| Fichier | Rôle |
|---|---|
| `world/Section`, `Chunk`, `World` | Stockage : 1 octet/bloc, sections 16³ vides = `null` |
| `world/TerrainGenerator`, `Noise` | Génération par bruit fBm, déterministe par seed |
| `world/Player`, `Raycast` | Physique AABB axe par axe (pas fixe 120 Hz), raycast DDA |
| `world/ChunkStreamer` | Monde infini : chargement du plus proche au plus loin, hystérésis, déchargement + sauvegarde |
| `world/storage/*` | NBT, fichiers région Anvil `.mca`, codec de chunk 1.16+/1.18+, `level.dat` |
| `world/Blocks` | Registre : couche de rendu, forme, collision, opacité, émission, teinte |
| `world/LightEngine` | Lumière ciel + blocs (BFS ajout/retrait incrémental, traverse les chunks) |
| `world/ChunkMesher` | **Greedy meshing** (fusion à lumière et AO égales), AO par sommet, plantes en croix, 8 o/sommet |
| `resource/PackSource`, `ResourceManager` | Packs dossier/zip/classpath empilés (le dernier gagne) |
| `resource/BlockAppearance` | `blockstates` → `models` (héritage `parent`, refs `#`) → textures + `tintindex` |
| `client/TextureArray` | Toutes les textures dans un `GL_TEXTURE_2D_ARRAY` (mipmaps, répétition sur quads fusionnés) |
| `client/WorldRenderer` | Maillage multi-thread, 3 passes (opaque avant→arrière, cutout, translucide arrière→avant), frustum culling, rendu relatif caméra, remaillage incrémental versionné |
| `client/Overlay` | Réticule et contour du bloc visé |

## Resource packs
Format Java Edition (`pack.mcmeta`, `assets/<ns>/blockstates|models|textures`). Le pack intégré
(`src/main/resources/defaultpack`) est une création originale, régénérable avec `python3 tools/gen_default_pack.py`.
Supporté : variantes (première, listes pondérées), `multipart` (première partie), parents, références `#`,
`tintindex` (couleur fixe en attendant les biomes), textures de taille quelconque (mise à l'échelle commune),
textures animées (première image). Non supporté pour l'instant : formes non cubiques, rotations, `.mcmeta` d'animation.

## Mondes et sauvegarde
Format Java Edition : `level.dat` + `region/r.X.Z.mca` (Anvil, zlib). Sauvegarde automatique toutes les 30 s
et à la fermeture ; seuls les chunks **modifiés** sont écrits (la génération est déterministe).
Un monde **vanilla** peut être ouvert (`--world chemin/vers/monde`) : il est en **lecture seule** par défaut,
car Mynecraft ne représente pas encore tout (hauteurs hors 0..127, entités, propriétés de blocs ; les blocs
inconnus sont approximés). `--allow-foreign-writes` force l'écriture, à vos risques.
