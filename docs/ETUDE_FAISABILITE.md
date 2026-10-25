# Mynecraft — Étude de faisabilité d'un MVP

> Objectif étudié : un clone de Minecraft Java Edition **compatible avec les add-ons classiques** (mods Forge/Fabric/NeoForge, plugins serveur, resource packs, data packs).

## 1. Ce que « compatible avec les add-ons » veut vraiment dire

Les « add-ons » Java se rangent en quatre familles, et la difficulté varie énormément de l'une à l'autre :

| Type | Ce qu'il contient | Couplage au jeu | Faisable dans un clone ? |
|---|---|---|---|
| **Resource packs** | Textures, modèles JSON, sons, langues, shaders | Format de fichiers documenté | ✅ Oui, assez facilement |
| **Data packs** | Recettes, loot tables, tags, advancements, worldgen JSON, fonctions `.mcfunction` | Format JSON + langage de commandes | ✅ Oui, effort moyen à élevé (commandes, worldgen) |
| **Plugins serveur** (Bukkit/Spigot/Paper) | Bytecode Java appelant une **API stable** (Bukkit) | API d'abstraction | ⚠️ Possible côté serveur, en réimplémentant l'API Bukkit |
| **Mods** (Forge, Fabric, NeoForge) | Bytecode Java qui **patche directement les classes de Minecraft** (Mixins, coremods) et dépend des noms internes (Mojang mappings / Yarn) | Couplage total au code de Mojang | ❌ Pas réaliste |

**Point clé :** un mod Fabric/Forge n'est pas un plugin qui parle à une API. Il injecte du code dans `net.minecraft.world.level.Level`, `Entity`, le renderer, etc., par Mixins ciblant des signatures de méthodes précises. Pour qu'il fonctionne, il faudrait que le clone reproduise **les mêmes classes, les mêmes noms, les mêmes signatures et le même comportement interne** — c'est-à-dire réécrire Minecraft quasiment à l'identique, version par version. C'est à la fois un travail colossal et un terrain juridique très risqué (voir §3).

## 2. Les options d'architecture

### Option A — Clone « from scratch » compatible fichiers (recommandée pour un MVP)
- Moteur maison (Java/LWJGL, ou Rust/Bevy, ou C++).
- Compatible **resource packs** et **data packs** (formats JSON publics).
- Compatible **protocole réseau** Java (optionnel) pour que le client officiel puisse se connecter, ou l'inverse.
- Système de mods **propre** (API stable, type Bukkit/Fabric API) — pas de compatibilité binaire avec les mods existants.
- Référence existante : **Minestom** (serveur Java from scratch, sans code Mojang), **Luanti / ex-Minetest** (moteur voxel libre, C++/Lua), **Cuberite** (serveur C++), **Valence** (serveur Rust).

### Option B — Serveur compatible plugins Bukkit
- Réimplémenter le serveur (protocole + logique de jeu) **et** l'API Bukkit/Paper.
- Les joueurs utilisent le client officiel ; les plugins Bukkit fonctionnent.
- Référence : Glowstone (abandonné — preuve de la difficulté de suivre les versions).

### Option C — Compatibilité mods Forge/Fabric
- Nécessite de redistribuer ou reproduire le code de Mojang → **impossible légalement** dans un projet indépendant, et irréaliste techniquement.
- L'alternative légale existe déjà : c'est… Minecraft + Fabric/NeoForge (le joueur fournit son jeu acheté).

## 3. Contraintes juridiques (bloquantes)

- Le code de Minecraft est propriétaire (Mojang/Microsoft). **Décompiler et redistribuer** le code ou les **assets** (textures, sons) est interdit par l'EULA.
- Les Mojang mappings sont publiés sous une licence restrictive (usage pour développement de mods, pas pour recréer le jeu).
- Le nom « Minecraft » et le logo sont des marques déposées ; « Mynecraft » est proche — à faire valider, prévoir un nom de repli.
- **Règle d'or :** implémentation « clean-room », assets originaux (ou libres, ex. textures de type MineClone/VoxeLibre sous licence compatible), aucun fichier Mojang dans le dépôt. Le joueur peut charger lui-même ses resource packs.

## 4. Proposition de MVP réaliste

**Périmètre MVP (≈ 3 à 6 mois pour 1–2 développeurs expérimentés) :**

1. **Moteur voxel** : chunks 16×16×384, meshing (greedy meshing), éclairage simple, frustum culling.
2. **Génération de monde** : bruit Perlin/Simplex, biomes basiques, grottes.
3. **Gameplay minimal** : déplacement, collisions, casser/poser des blocs, inventaire, hotbar, mode créatif + survie simplifiée.
4. **Sauvegarde** : format Anvil (`.mca`, NBT) → permet d'ouvrir des mondes Java existants (format documenté publiquement).
5. **Resource packs** : chargement de `pack.mcmeta`, `assets/*/blockstates`, `models`, `textures` → compatibilité directe avec les packs existants.
6. **Data packs (sous-ensemble)** : recettes, tags, loot tables.
7. **API de mods maison** : chargement de JARs (si Java) ou scripts (Lua/WASM), événements (bloc cassé, joueur rejoint…), registre de blocs/items.

**Hors MVP :** redstone complète, mobs avancés, Nether/End, multijoueur, compatibilité protocole, commandes complètes.

## 5. Choix technologique recommandé

| Critère | Java + LWJGL 3 | Rust + wgpu/Bevy |
|---|---|---|
| Proximité de l'écosystème moddeur | ✅ Moddeurs Java, possibilité d'API « Fabric-like » | ❌ Mods en WASM/Lua |
| Performances | Correctes (GC) | Excellentes |
| Réutilisation de briques | Minestom (serveur), libs NBT | Valence, crates NBT/Anvil |

→ **Java 21 + LWJGL 3 (OpenGL/Vulkan) + Gradle**, avec une API de mods inspirée de Fabric API (événements + registres). C'est le meilleur compromis pour attirer les moddeurs existants, qui pourraient **porter** leurs mods à moindre coût, à défaut de compatibilité binaire.

## 6. Conclusion

| Objectif | Verdict |
|---|---|
| Clone jouable de Minecraft (MVP) | ✅ Faisable |
| Compatible resource packs | ✅ Faisable, fort impact pour peu d'effort |
| Compatible data packs | ✅ Faisable par étapes |
| Compatible mondes Java (Anvil/NBT) | ✅ Faisable |
| Compatible plugins Bukkit | ⚠️ Possible côté serveur, très coûteux à maintenir |
| Compatible mods Forge/Fabric | ❌ Non réaliste (techniquement et juridiquement) |

**Recommandation :** redéfinir « compatible add-ons » en **compatible resource packs + data packs + mondes**, avec une **API de mods propre** et documentée. Prochaine étape proposée : initialiser le projet Gradle/LWJGL et livrer un premier jalon « monde voxel affiché avec un resource pack chargé ».
