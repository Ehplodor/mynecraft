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
gradle test                                  # tests + micro-benchmark génération/maillage
```

Contrôles : souris pour regarder, `ZQSD`/`WASD` (position physique des touches), `Espace`/`Maj` monter/descendre, `Ctrl` sprint, `Échap` quitter.

## Architecture (M0)
| Fichier | Rôle |
|---|---|
| `world/Section`, `Chunk`, `World` | Stockage : 1 octet/bloc, sections 16³ vides = `null` |
| `world/TerrainGenerator`, `Noise` | Génération par bruit fBm, déterministe par seed |
| `world/ChunkMesher` | **Greedy meshing**, sommet compacté sur **un seul `int`** (4 o/sommet) |
| `client/WorldRenderer` | Génération/maillage multi-thread, upload GPU avec budget/frame, frustum culling, rendu relatif caméra |
