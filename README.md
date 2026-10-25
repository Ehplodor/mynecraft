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
gradle run --args="--screenshot shot.png"    # rend une image puis quitte
gradle run --args="--pack MonPack.zip"       # resource pack Java (zip ou dossier), répétable
gradle test                                  # tests + micro-benchmark génération/maillage
```

Contrôles : souris pour regarder, `ZQSD`/`WASD` (position physique des touches), `Espace` sauter,
`F` mode vol (`Espace`/`Maj` monter/descendre), `Ctrl` sprint, clic gauche casser, clic droit poser,
`1`–`5` choisir le bloc, `Échap` quitter.

## Architecture
| Fichier | Rôle |
|---|---|
| `world/Section`, `Chunk`, `World` | Stockage : 1 octet/bloc, sections 16³ vides = `null` |
| `world/TerrainGenerator`, `Noise` | Génération par bruit fBm, déterministe par seed |
| `world/Player`, `Raycast` | Physique AABB axe par axe (pas fixe 120 Hz), raycast DDA |
| `world/ChunkMesher` | **Greedy meshing**, sommet compacté sur **un seul `int`** (4 o/sommet) |
| `resource/PackSource`, `ResourceManager` | Packs dossier/zip/classpath empilés (le dernier gagne) |
| `resource/BlockAppearance` | `blockstates` → `models` (héritage `parent`, refs `#`) → textures + `tintindex` |
| `client/TextureArray` | Toutes les textures dans un `GL_TEXTURE_2D_ARRAY` (mipmaps, répétition sur quads fusionnés) |
| `client/WorldRenderer` | Génération/maillage multi-thread, upload GPU avec budget/frame, frustum culling, rendu relatif caméra, remaillage incrémental versionné |
| `client/Overlay` | Réticule et contour du bloc visé |

## Resource packs
Format Java Edition (`pack.mcmeta`, `assets/<ns>/blockstates|models|textures`). Le pack intégré
(`src/main/resources/defaultpack`) est une création originale, régénérable avec `python3 tools/gen_default_pack.py`.
Supporté : variantes (première, listes pondérées), `multipart` (première partie), parents, références `#`,
`tintindex` (couleur fixe en attendant les biomes), textures de taille quelconque (mise à l'échelle commune),
textures animées (première image). Non supporté pour l'instant : formes non cubiques, rotations, `.mcmeta` d'animation.
