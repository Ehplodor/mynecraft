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

## M4 — Plus de blocs et rendu (proposé)
- Registre de blocs extensible (états/propriétés), transparence (verre, feuilles, eau), modèles non cubiques
- Éclairage (ciel + blocs) par propagation, ambient occlusion dans le sommet

## Pistes d'optimisation continues
- Multi-draw indirect (`glMultiDrawElementsIndirect`) + un seul gros VBO → 1 draw call
- Occlusion culling par graphe de visibilité des sections (approche « cave culling »)
- Palette par section (4 bits/bloc) pour la mémoire, ambient occlusion baked dans les 4 bits restants du sommet
- Benchmarks JMH en CI pour détecter les régressions
