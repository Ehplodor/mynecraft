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
    rnd = random.Random(7)
    for x in range(S):
        for y in range(3 + rnd.randint(0, 2)):
            g = rnd.randint(-15, 15)
            side.putpixel((x, y), (95 + g, 150 + g, 60 + g))
    save("grass_block_side", side)

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
        "textures": {"particle": "block/dirt", "bottom": "block/dirt",
                     "top": "block/grass_block_top", "side": "block/grass_block_side"},
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
            "down": {"texture": "#bottom", "cullface": "down"},
            "up": {"texture": "#top", "cullface": "up", "tintindex": 0},
            "north": {"texture": "#side", "cullface": "north"},
            "south": {"texture": "#side", "cullface": "south"},
            "west": {"texture": "#side", "cullface": "west"},
            "east": {"texture": "#side", "cullface": "east"}}}]})
    for name in ("stone", "dirt", "sand", "bedrock"):
        write_json(f"models/block/{name}.json", {"parent": "block/cube_all", "textures": {"all": f"block/{name}"}})
    for name in ("stone", "dirt", "sand", "bedrock", "grass_block"):
        write_json(f"blockstates/{name}.json", {"variants": {"": {"model": f"minecraft:block/{name}"}}})


if __name__ == "__main__":
    main()
