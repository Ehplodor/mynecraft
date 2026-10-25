# Feuille de route

## ✅ M0 — Monde voxel affiché (ce jalon)
- Projet Gradle + LWJGL 3 (OpenGL 3.3 core) + JOML
- Génération procédurale, chunks 16×128×16 (8 sections)
- Greedy meshing, sommets 32 bits, index buffer partagé, frustum culling, fog
- Pipeline multi-thread (génération + maillage hors thread de rendu)
- Mesures M0 (CPU conteneur, JIT froid) : ~0,1 ms/chunk généré, ~0,25 ms/section maillée, ~33 quads/section

## M1 — Resource packs
- Lecture `pack.mcmeta`, `blockstates`, `models`, `textures` → texture array GPU (greedy meshing compatible via UV répétées)
- Atlas/array construit au chargement

## M2 — Interaction
- Collisions AABB, gravité, raycast (DDA), casser/poser, remaillage incrémental d'une section

## M3 — Monde infini & persistance
- Chargement/déchargement dynamique autour du joueur, priorité par distance
- Sauvegarde/lecture format Anvil (NBT, `.mca`)

## Pistes d'optimisation continues
- Multi-draw indirect (`glMultiDrawElementsIndirect`) + un seul gros VBO → 1 draw call
- Occlusion culling par graphe de visibilité des sections (approche « cave culling »)
- Palette par section (4 bits/bloc) pour la mémoire, ambient occlusion baked dans les 4 bits restants du sommet
- Benchmarks JMH en CI pour détecter les régressions
