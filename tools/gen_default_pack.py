"""Generates the original built-in resource pack (no Mojang assets).

Usage: python3 tools/gen_default_pack.py
Writes into src/main/resources/defaultpack/ using the vanilla Java pack layout.
"""
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "defaultpack")
NS = os.path.join(ROOT, "assets", "minecraft")
S = 16


def noisy(base, spread, seed, gray=False):
    rnd = random.Random(seed)
    img = Image.new("RGB", (S, S))
    for y in range(S):
        for x in range(S):
            d = rnd.randint(-spread, spread)
            img.putpixel((x, y), tuple(max(0, min(255, c + d)) for c in base))
    return img


def rgba(fn, seed):
    """Image from fn(x, y, rnd) -> (r, g, b, a)."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (S, S))
    for y in range(S):
        for x in range(S):
            img.putpixel((x, y), fn(x, y, rnd))
    return img


def clamp(c):
    return max(0, min(255, c))


def write_json(rel, data):
    path = os.path.join(NS, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def save(name, img):
    path = os.path.join(NS, "textures", "block", name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def main():
    save("stone", noisy((118, 118, 122), 14, 1))
    save("dirt", noisy((122, 86, 58), 16, 2))
    save("sand", noisy((219, 205, 145), 10, 3))
    save("bedrock", noisy((60, 60, 60), 40, 4))
    # Grass top is grayscale: tinted at render time (tintindex 0), like vanilla packs expect.
    save("grass_block_top", noisy((150, 150, 150), 22, 5))
    side = noisy((122, 86, 58), 16, 6)
    fringe = [3 + random.Random(7 + x).randint(0, 2) for x in range(S)]  # shared with the overlay
    rnd = random.Random(7)
    for x in range(S):
        for y in range(fringe[x]):
            g = rnd.randint(-15, 15)
            side.putpixel((x, y), (95 + g, 150 + g, 60 + g))
    save("grass_block_side", side)
    # Grayscale fringe, tinted per biome and drawn over the side (vanilla layout).
    over = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rnd = random.Random(8)
    for x in range(S):
        for y in range(fringe[x]):
            v = 150 + rnd.randint(-20, 20)
            over.putpixel((x, y), (v, v, v, 255))
    path = os.path.join(NS, "textures", "block", "grass_block_side_overlay.png")
    over.save(path)

    save("gravel", noisy((128, 120, 116), 30, 8))
    save("cobblestone", rgba(lambda x, y, r: (lambda v: (v, v, v + 4, 255))(
        clamp(110 + r.randint(-12, 12) - (40 if (x % 8 == 0 or (y + (x // 8) * 4) % 6 == 0) else 0))), 9))
    save("oak_planks", rgba(lambda x, y, r: (lambda g: (clamp(160 + g), clamp(125 + g), clamp(75 + g), 255))(
        r.randint(-8, 8) - (35 if y % 4 == 3 else 0) - (25 if (x + (y // 4) * 5) % 16 == 0 else 0)), 10))
    save("oak_log", rgba(lambda x, y, r: (lambda g: (clamp(100 + g), clamp(78 + g), clamp(48 + g), 255))(
        r.randint(-10, 10) - (25 if x % 3 == 0 else 0)), 11))
    save("oak_log_top", rgba(lambda x, y, r: (lambda d: (lambda g: (clamp(170 + g), clamp(135 + g), clamp(85 + g), 255))(
        r.randint(-6, 6) - (30 if int(d) % 3 == 0 else 0)) if d < 7 else (95, 74, 45, 255))(
        ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5), 12))
    # Foliage, grass plant and water are grayscale: tinted per biome at render time.
    save("oak_leaves", rgba(lambda x, y, r: (lambda v: (v, v, v, 0 if r.random() < 0.18 else 255))(
        clamp(120 + r.randint(-35, 35))), 13))
    save("glass", rgba(lambda x, y, r: (215, 235, 240, 255) if x in (0, 15) or y in (0, 15)
        else (255, 255, 255, 200) if (x - y) in (3, 4) and 2 < x < 10 else (0, 0, 0, 0), 14))
    save("short_grass", rgba(lambda x, y, r: (lambda v: (v, v, v, 255))(clamp(140 + r.randint(-30, 30)))
        if (x % 3 != 1 and y > (5 + (x * 7) % 6)) else (0, 0, 0, 0), 15))
    save("poppy", rgba(lambda x, y, r: (200, 30, 30, 255) if (x - 7.5) ** 2 + (y - 5) ** 2 < 9
        else (40, 120, 40, 255) if x in (7, 8) and y >= 7 else (0, 0, 0, 0), 16))
    save("glowstone", rgba(lambda x, y, r: (lambda v: (clamp(v + 40), clamp(v), clamp(v - 80), 255))(
        170 + r.randint(-50, 50)), 17))
    save("water_still", rgba(lambda x, y, r: (lambda v: (v, v, v, 180))(clamp(190 + r.randint(-20, 20))), 18))

    # Biome colormaps (vanilla layout: x = 1 - temperature, y = 1 - downfall * temperature).
    def colormap(name, hot_dry, cold, wet):
        img = Image.new("RGB", (256, 256))
        for y in range(256):
            for x in range(256):
                t = 1 - x / 255
                d = 1 - y / 255
                c = [cold[i] * (1 - t) + hot_dry[i] * t * (1 - d) + wet[i] * t * d for i in range(3)]
                img.putpixel((x, y), tuple(clamp(int(v)) for v in c))
        path = os.path.join(NS, "textures", "colormap", name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    colormap("grass", (190, 182, 72), (112, 172, 84), (62, 196, 40))
    colormap("foliage", (170, 160, 40), (90, 150, 70), (30, 170, 10))

    # Sky (vanilla layout): sun, 8 moon phases in a 4x2 grid, cloud map (white = cloud).
    env = os.path.join(NS, "textures", "environment")
    os.makedirs(env, exist_ok=True)
    sun = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    for y in range(32):
        for x in range(32):
            d = max(abs(x - 15.5), abs(y - 15.5))
            if d < 8:
                sun.putpixel((x, y), (255, 250, 200, 255))
            elif d < 12:
                sun.putpixel((x, y), (255, 210, 90, int(200 * (12 - d) / 4)))
    sun.save(os.path.join(env, "sun.png"))
    moon = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    for phase in range(8):
        ox, oy = (phase % 4) * 32, (phase // 4) * 32
        # Lit fraction: full at phase 0, new at 4; the terminator sweeps across the disc.
        lit = abs(4 - phase) / 4
        for y in range(32):
            for x in range(32):
                dx, dy = x - 15.5, y - 15.5
                if dx * dx + dy * dy < 100:
                    side = (dx + 10) / 20 if phase < 4 else (10 - dx) / 20
                    if side <= lit + 1e-6:
                        v = 220 - (15 if (x * 7 + y * 3) % 11 == 0 else 0)
                        moon.putpixel((ox + x, oy + y), (v, v, v + 10, 255))
    moon.save(os.path.join(env, "moon_phases.png"))
    rnd = random.Random(42)
    blobs = [(rnd.randint(0, 255), rnd.randint(0, 255), rnd.randint(4, 14), rnd.randint(2, 6)) for _ in range(140)]
    clouds = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    for y in range(256):
        for x in range(256):
            for bx, by, rx, ry in blobs:
                ddx = min(abs(x - bx), 256 - abs(x - bx)) / rx
                ddy = min(abs(y - by), 256 - abs(y - by)) / ry
                if ddx * ddx + ddy * ddy < 1:
                    clouds.putpixel((x, y), (255, 255, 255, 255))
                    break
    clouds.save(os.path.join(env, "clouds.png"))

    with open(os.path.join(ROOT, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"pack_format": 34, "description": "Mynecraft default (original art)"}}, f, indent=2)
        f.write("\n")

    faces = {"down": "#down", "up": "#up", "north": "#north", "south": "#south", "west": "#west", "east": "#east"}
    write_json("models/block/block.json", {})
    write_json("models/block/cube.json", {
        "parent": "block/block",
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                      "faces": {k: {"texture": v, "cullface": k} for k, v in faces.items()}}]})
    write_json("models/block/cube_all.json", {
        "parent": "block/cube",
        "textures": {"particle": "#all", **{k: "#all" for k in faces}}})
    write_json("models/block/cube_bottom_top.json", {
        "parent": "block/cube",
        "textures": {"particle": "#side", "down": "#bottom", "up": "#top",
                     "north": "#side", "south": "#side", "west": "#side", "east": "#side"}})
    write_json("models/block/grass_block.json", {
        "parent": "block/block",
        "textures": {"particle": "block/dirt", "bottom": "block/dirt", "top": "block/grass_block_top",
                     "side": "block/grass_block_side", "overlay": "block/grass_block_side_overlay"},
        "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                "down": {"texture": "#bottom", "cullface": "down"},
                "up": {"texture": "#top", "cullface": "up", "tintindex": 0},
                "north": {"texture": "#side", "cullface": "north"},
                "south": {"texture": "#side", "cullface": "south"},
                "west": {"texture": "#side", "cullface": "west"},
                "east": {"texture": "#side", "cullface": "east"}}},
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                "north": {"texture": "#overlay", "tintindex": 0, "cullface": "north"},
                "south": {"texture": "#overlay", "tintindex": 0, "cullface": "south"},
                "west": {"texture": "#overlay", "tintindex": 0, "cullface": "west"},
                "east": {"texture": "#overlay", "tintindex": 0, "cullface": "east"}}}]})
    write_json("models/block/cube_column.json", {
        "parent": "block/cube",
        "textures": {"particle": "#side", "down": "#end", "up": "#end",
                     "north": "#side", "south": "#side", "west": "#side", "east": "#side"}})
    write_json("models/block/leaves.json", {
        "parent": "block/block",
        "textures": {"particle": "#all"},
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                      "faces": {k: {"texture": "#all", "cullface": k, "tintindex": 0} for k in faces}}]})
    for name, tint in (("cross", False), ("tinted_cross", True)):
        face = {"uv": [0, 0, 16, 16], "texture": "#cross"}
        if tint:
            face["tintindex"] = 0
        write_json(f"models/block/{name}.json", {
            "parent": "block/block", "ambientocclusion": False,
            "textures": {"particle": "#cross"},
            "elements": [
                {"from": [0.8, 0, 8], "to": [15.2, 16, 8], "shade": False,
                 "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True},
                 "faces": {"north": face, "south": face}},
                {"from": [8, 0, 0.8], "to": [8, 16, 15.2], "shade": False,
                 "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True},
                 "faces": {"west": face, "east": face}}]})
    write_json("models/block/water.json", {"textures": {"particle": "block/water_still"}})
    for name in ("stone", "dirt", "sand", "bedrock", "gravel", "cobblestone", "oak_planks", "glass", "glowstone"):
        write_json(f"models/block/{name}.json", {"parent": "block/cube_all", "textures": {"all": f"block/{name}"}})
    write_json("models/block/oak_log.json", {"parent": "block/cube_column",
                                             "textures": {"end": "block/oak_log_top", "side": "block/oak_log"}})
    write_json("models/block/oak_leaves.json", {"parent": "block/leaves", "textures": {"all": "block/oak_leaves"}})
    write_json("models/block/short_grass.json", {"parent": "block/tinted_cross", "textures": {"cross": "block/short_grass"}})
    write_json("models/block/poppy.json", {"parent": "block/cross", "textures": {"cross": "block/poppy"}})
    # Slabs (vanilla parents) and the oak slab models.
    for name, lo, hi in (("slab", 0, 8), ("slab_top", 8, 16)):
        write_json(f"models/block/{name}.json", {
            "parent": "block/block",
            "textures": {"particle": "#side"},
            "elements": [{"from": [0, lo, 0], "to": [16, hi, 16], "faces": {
                "down": {"texture": "#bottom", **({"cullface": "down"} if lo == 0 else {})},
                "up": {"texture": "#top", **({"cullface": "up"} if hi == 16 else {})},
                "north": {"texture": "#side", "cullface": "north"},
                "south": {"texture": "#side", "cullface": "south"},
                "west": {"texture": "#side", "cullface": "west"},
                "east": {"texture": "#side", "cullface": "east"}}}]})
    planks = {"bottom": "block/oak_planks", "top": "block/oak_planks", "side": "block/oak_planks"}
    write_json("models/block/oak_slab.json", {"parent": "block/slab", "textures": planks})
    write_json("models/block/oak_slab_top.json", {"parent": "block/slab_top", "textures": planks})
    write_json("blockstates/oak_slab.json", {"variants": {
        "type=bottom": {"model": "minecraft:block/oak_slab"},
        "type=top": {"model": "minecraft:block/oak_slab_top"},
        "type=double": {"model": "minecraft:block/oak_planks"}}})
    write_json("blockstates/oak_log.json", {"variants": {
        "axis=x": {"model": "minecraft:block/oak_log", "x": 90, "y": 90},
        "axis=y": {"model": "minecraft:block/oak_log"},
        "axis=z": {"model": "minecraft:block/oak_log", "x": 90}}})
    for name in ("stone", "dirt", "sand", "bedrock", "grass_block", "gravel", "cobblestone", "oak_planks", "glass",
                 "glowstone", "oak_leaves", "short_grass", "poppy", "water"):
        write_json(f"blockstates/{name}.json", {"variants": {"": {"model": f"minecraft:block/{name}"}}})


if __name__ == "__main__":
    main()
