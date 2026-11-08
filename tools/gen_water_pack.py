"""The water slice: textures, models and blockstates of the reeds, the willow cutting and the willow
leaves, the sluice gate (closed, open), the stone channel, the silt and the driftwood; the sprite of
the willow bark; their French names, and the data (reeds count as thatching straw, recipes of the
gate and the channel) in the default packs.

Usage: python3 tools/gen_water_pack.py   (after gen_default_pack.py, gen_life_pack.py and gen_herd_pack.py; writes only its own files)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_default_pack import NS, clamp, rgba, save, write_json  # noqa: E402
from gen_life_pack import DATA, data, shaped  # noqa: E402


def blocks():
    # Reeds: tall thin stalks, a brown cattail head on a few of them.
    def reeds(x, y, r):
        if x in (2, 5, 9, 12) or (x in (7, 14) and y > 3):
            if x in (5, 12) and 1 <= y <= 4:
                return (clamp(110 + r.randint(-10, 10)), clamp(70 + r.randint(-8, 8)), 35, 255)  # cattail
            if y == 0 and x in (5, 12):
                return (0, 0, 0, 0)
            g = r.randint(-18, 18)
            return (clamp(105 + g), clamp(140 + g), clamp(60 + g // 2), 255)
        if (x, y) in ((3, 6), (4, 7), (10, 5), (11, 4), (8, 9), (13, 8)):  # leaves bending off the stalks
            return (120, 150, 70, 255)
        return (0, 0, 0, 0)
    save("reeds", rgba(reeds, 501))

    # The willow cutting: a slender stem with long narrow leaves hanging down.
    def cutting(x, y, r):
        if x == 8 and y >= 4:
            return (110, 85, 55, 255)
        for cx, top in ((6, 3), (10, 4), (5, 7), (11, 8)):
            if x == cx and top <= y <= top + 5:
                return (clamp(150 + r.randint(-15, 15)), clamp(175 + r.randint(-15, 15)), 110, 255)
        return (0, 0, 0, 0)
    save("willow_sapling", rgba(cutting, 502))

    # Willow leaves: long silvery-green strands with gaps, like a curtain.
    def leaves(x, y, r):
        if (x + (y // 4)) % 3 == 2 and r.random() < 0.7:
            return (0, 0, 0, 0)
        g = r.randint(-20, 20)
        silver = 25 if (x * 7 + y) % 5 == 0 else 0
        return (clamp(125 + g + silver), clamp(160 + g + silver), clamp(95 + g // 2 + silver), 255)
    save("willow_leaves", rgba(leaves, 503))

    for n in ("reeds", "willow_sapling"):
        write_json(f"models/block/{n}.json", {"parent": "block/cross", "textures": {"cross": f"block/{n}"}})
    write_json("models/block/willow_leaves.json", {"parent": "block/leaves", "textures": {"all": "block/willow_leaves"}})
    for n in ("reeds", "willow_sapling", "willow_leaves"):
        write_json(f"blockstates/{n}.json", {"variants": {"": {"model": f"minecraft:block/{n}"}}})

    def plank(x, y, r):
        g = r.randint(-12, 12) - (18 if y % 4 == 3 else 0)
        return (clamp(150 + g), clamp(112 + g), clamp(66 + g // 2), 255)

    # The sluice gate: closed, a board of planks banded with iron; open, the frame alone (posts and lintel).
    def closed(x, y, r):
        if y in (2, 3, 12, 13):
            return (clamp(95 + r.randint(-8, 8)), clamp(95 + r.randint(-8, 8)), clamp(100 + r.randint(-8, 8)), 255)
        return plank(x, y, r)
    save("sluice", rgba(closed, 504))

    def frame(x, y, r):
        if x <= 2 or x >= 13 or y <= 2:
            return plank(x, y, r)
        return (0, 0, 0, 0)
    save("sluice_open", rgba(frame, 505))
    write_json("models/block/sluice.json", {"parent": "block/cube_all", "textures": {"all": "block/sluice"}})
    write_json("models/block/sluice_open.json", {"parent": "block/cube_all", "textures": {"all": "block/sluice_open"}})
    write_json("blockstates/sluice.json", {"variants": {"open=false": {"model": "minecraft:block/sluice"},
                                                        "open=true": {"model": "minecraft:block/sluice_open"}}})

    def stone(x, y, r):
        g = r.randint(-14, 14)
        return (clamp(125 + g), clamp(125 + g), clamp(128 + g), 255)

    # The stone channel: dressed stone, a groove along the top where the water runs.
    def channel_top(x, y, r):
        if 4 <= x <= 11:
            g = r.randint(-10, 10) - (25 if x in (4, 11) else 10)
            return (clamp(105 + g), clamp(105 + g), clamp(110 + g), 255)
        return stone(x, y, r)
    save("stone_channel_top", rgba(channel_top, 506))
    save("stone_channel", rgba(lambda x, y, r: stone(x, y, r) if y % 8 != 7 else (95, 95, 98, 255), 507))
    write_json("models/block/stone_channel.json", {"parent": "block/cube_column",
                                                   "textures": {"end": "block/stone_channel_top", "side": "block/stone_channel"}})

    # Silt: fine, grey-brown, faintly banded; driftwood: bleached, silver-grey grain.
    save("silt", rgba(lambda x, y, r: (clamp(128 + r.randint(-9, 9) + (6 if y % 5 == 0 else 0)),
                                       clamp(112 + r.randint(-9, 9)), clamp(88 + r.randint(-7, 7)), 255), 508))
    save("driftwood", rgba(lambda x, y, r: (clamp(178 + r.randint(-14, 10) - (22 if x % 5 == 2 else 0)),
                                            clamp(170 + r.randint(-14, 10) - (22 if x % 5 == 2 else 0)),
                                            clamp(155 + r.randint(-12, 8) - (20 if x % 5 == 2 else 0)), 255), 509))
    for n in ("stone_channel", "silt", "driftwood"):
        if n != "stone_channel":
            write_json(f"models/block/{n}.json", {"parent": "block/cube_all", "textures": {"all": f"block/{n}"}})
        write_json(f"blockstates/{n}.json", {"variants": {"": {"model": f"minecraft:block/{n}"}}})


def items():
    from PIL import Image
    out = os.path.join(NS, "textures", "item")
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    # Willow bark: two curled strips, grey-brown outside, pale inside.
    for x0, y0 in ((3, 3), (8, 6)):
        for i in range(8):
            x, y = x0 + i // 2, y0 + i
            if 0 <= x < 15 and y < 16:
                im.putpixel((x, y), (120, 105, 85, 255))
                im.putpixel((x + 1, y), (215, 195, 150, 255))
                im.putpixel((x + 2, y), (95, 80, 65, 255))
    im.save(os.path.join(out, "willow_bark.png"))


def names():
    path = os.path.join(NS, "lang", "fr_fr.json")
    with open(path, encoding="utf-8") as f:
        lang = json.load(f)
    lang["block.minecraft.reeds"] = "Roseaux"
    lang["block.minecraft.willow_sapling"] = "Bouture de saule"
    lang["block.minecraft.willow_leaves"] = "Feuilles de saule"
    lang["item.minecraft.willow_bark"] = "Écorce de saule"
    lang["block.minecraft.sluice"] = "Vanne"
    lang["block.minecraft.stone_channel"] = "Rigole de pierre"
    lang["block.minecraft.silt"] = "Limon"
    lang["block.minecraft.driftwood"] = "Bois flotté"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write("\n")


def recipes():
    # Reeds thatch a roof as well as straw.
    data("tags/item/thatch_straw.json", {"values": ["minecraft:plant_fiber", "minecraft:wheat", "minecraft:reeds"]})
    shaped("sluice", ["PIP", "PPP"], {"P": "#planks", "I": "iron_ingot"}, "sluice", skill="building", level=1)
    shaped("stone_channel", ["C C", "CCC"], {"C": "cobblestone"}, "stone_channel", count=3, skill="building")
    shaped("oak_planks_from_driftwood", ["D"], {"D": "driftwood"}, "oak_planks", count=2, skill="woodcutting")
    files = []
    for root, _, ns in os.walk(DATA):
        for n in ns:
            if n != "index.txt":
                files.append(os.path.relpath(os.path.join(root, n), DATA).replace(os.sep, "/"))
    with open(os.path.join(DATA, "index.txt"), "w") as f:
        f.write("\n".join(sorted(files)) + "\n")


if __name__ == "__main__":
    blocks()
    items()
    names()
    recipes()
