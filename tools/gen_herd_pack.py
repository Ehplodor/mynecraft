"""Husbandry (élevage): textures, models and blockstates of the nest and the beehive, sprites of the
new items (shears, fleece, jug, milk, cheese, eggs, mutton, chicken, honey), their French names,
and their recipes in the default data pack.

Usage: python3 tools/gen_herd_pack.py   (after gen_default_pack.py and gen_life_pack.py; writes only its own files)
"""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_default_pack import NS, clamp, rgba, save, write_json  # noqa: E402
from gen_life_pack import DATA, process, shaped, wood  # noqa: E402


def straw(x, y, r):
    g = r.randint(-25, 20)
    return (clamp(205 + g), clamp(175 + g), clamp(90 + g // 2), 255)


def blocks():
    # The nest: a ring of straw low on the ground, drawn as a cross plant like the snare; an egg in it.
    def nest(egg):
        def px(x, y, r):
            if egg and 5 <= x <= 10 and 7 <= y <= 12 and (x - 7.5) ** 2 / 7 + (y - 9.5) ** 2 / 9 < 1:
                return (clamp(240 + r.randint(-8, 8)), clamp(225 + r.randint(-8, 8)), clamp(195 + r.randint(-8, 8)), 255)
            if y >= 11 and (y >= 13 or x < 4 or x > 11):
                return straw(x, y, r)
            if y >= 10 and r.random() < 0.35:
                return straw(x, y, r)
            return (0, 0, 0, 0)
        return px
    save("nest", rgba(nest(False), 301))
    save("nest_egg", rgba(nest(True), 302))
    for n in ("nest", "nest_egg"):
        write_json(f"models/block/{n}.json", {"parent": "block/cross", "textures": {"cross": f"block/{n}"}})
    write_json("blockstates/nest.json", {"variants": {"full=false": {"model": "minecraft:block/nest"},
                                                       "full=true": {"model": "minecraft:block/nest_egg"}}})

    # The beehive: a box of planks under a straw roof, its entrance dark, then busy, then golden with honey.
    save("beehive_top", rgba(straw, 303))
    for i, state in enumerate(("empty", "bees", "honey")):
        def side(x, y, r, state=state):
            if y <= 2:
                return straw(x, y, r)
            if 6 <= x <= 9 and 10 <= y <= 11:
                return (30, 22, 14, 255)
            if state == "bees" and r.random() < 0.06 and 5 <= y <= 13:
                return (230, 180, 30, 255)
            if state == "honey" and y >= 12 and (x + y) % 3 == 0:
                return (235, 165, 25, 255)
            return wood(x, y, r, (175, 135, 80))
        save(f"beehive_{state}", rgba(side, 304 + i))
        write_json(f"models/block/beehive_{state}.json", {
            "parent": "block/cube", "textures": {"particle": f"block/beehive_{state}", "up": "block/beehive_top",
                                                 "down": "block/oak_planks", "north": f"block/beehive_{state}",
                                                 "south": f"block/beehive_{state}", "east": f"block/beehive_{state}",
                                                 "west": f"block/beehive_{state}"}})
    write_json("blockstates/beehive.json", {"variants": {f"state={s}": {"model": f"minecraft:block/beehive_{s}"}
                                                         for s in ("empty", "bees", "honey")}})


def items():
    from PIL import Image
    out = os.path.join(NS, "textures", "item")

    def img():
        return Image.new("RGBA", (16, 16), (0, 0, 0, 0))

    def put(im, pts, c):
        for x, y in pts:
            if 0 <= x < 16 and 0 <= y < 16:
                im.putpixel((x, y), c + (255,))

    def blob(c, r=5.5, cx=7.5, cy=8.5, seed=0, spots=None, sx=1.0, sy=1.0):
        im = img()
        rr = random.Random(seed)
        for y in range(16):
            for x in range(16):
                if ((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2 < r * r:
                    d = rr.randint(-12, 12) - (22 if (x - cx) + (y - cy) > r * 0.6 else 0)
                    col = spots if spots and rr.random() < 0.15 else c
                    im.putpixel((x, y), tuple(max(0, min(255, v + d)) for v in col) + (255,))
        return im

    shapes = {}
    im = img()  # shears: two crossed iron blades with wooden grips
    put(im, [(3 + i, 3 + i) for i in range(7)] + [(12 - i, 3 + i) for i in range(7)], (205, 205, 212))
    put(im, [(2, 12), (3, 13), (2, 13), (3, 12), (12, 12), (13, 13), (12, 13), (13, 12), (4, 11), (11, 11)], (110, 78, 40))
    shapes["shears"] = im
    shapes["fleece"] = blob((235, 230, 215), 5.5, seed=401, spots=(250, 248, 240), sy=0.8)
    im = img()  # jug: a terracotta pitcher with a handle
    put(im, [(x, y) for x in range(5, 11) for y in range(5, 14) if not (y == 5 and x in (5, 10))], (165, 92, 62))
    put(im, [(6, 3), (7, 3), (8, 3), (9, 3), (6, 4), (9, 4)], (165, 92, 62))
    put(im, [(11, 7), (12, 8), (12, 9), (12, 10), (11, 11)], (140, 75, 50))
    shapes["jug"] = im
    im = shapes["jug"].copy()
    put(im, [(7, 3), (8, 3)], (250, 250, 245))  # white at the brim
    put(im, [(x, 4) for x in range(6, 10)], (245, 245, 240))
    shapes["milk"] = im
    im = img()  # cheese: a pale wheel with a wedge cut
    put(im, [(x, y) for x in range(3, 13) for y in range(6, 12) if not (x > 9 and y < 9)], (235, 205, 110))
    put(im, [(x, 6) for x in range(3, 10)], (245, 220, 140))
    put(im, [(5, 8), (8, 10), (6, 10)], (200, 165, 70))
    shapes["cheese"] = im
    shapes["egg"] = blob((240, 228, 205), 4.5, seed=402, sx=0.85, sy=1.1)
    im = blob((250, 250, 245), 6, seed=403, sy=0.75)
    put(im, [(x, y) for x in range(6, 10) for y in range(7, 11)], (245, 190, 40))
    shapes["cooked_egg"] = im
    shapes["mutton"] = blob((195, 75, 75), 5.5, seed=404, spots=(240, 225, 215))
    shapes["cooked_mutton"] = blob((140, 85, 50), 5.5, seed=405, spots=(110, 60, 30))
    shapes["chicken"] = blob((230, 175, 150), 5, seed=406)
    shapes["cooked_chicken"] = blob((195, 130, 60), 5, seed=407, spots=(160, 95, 40))
    im = img()  # honey: a little pot of gold
    put(im, [(x, y) for x in range(4, 12) for y in range(6, 14)], (225, 160, 30))
    put(im, [(x, 5) for x in range(4, 12)], (150, 100, 50))
    put(im, [(5, 7), (6, 8), (9, 9)], (250, 205, 80))
    shapes["honey"] = im
    for n, im in shapes.items():
        im.save(os.path.join(out, n + ".png"))


def names():
    path = os.path.join(NS, "lang", "fr_fr.json")
    with open(path, encoding="utf-8") as f:
        lang = json.load(f)
    lang["block.minecraft.nest"] = "Nid de paille"
    lang["block.minecraft.beehive"] = "Ruche"
    for k, v in {"shears": "Cisailles", "fleece": "Toison", "jug": "Cruche", "milk": "Lait de chèvre",
                 "cheese": "Fromage de chèvre", "egg": "Œuf", "cooked_egg": "Œuf cuit", "mutton": "Viande de mouton crue",
                 "cooked_mutton": "Mouton rôti", "chicken": "Poulet cru", "cooked_chicken": "Poulet rôti",
                 "honey": "Miel"}.items():
        lang["item.minecraft." + k] = v
    with open(path, "w", encoding="utf-8") as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write("\n")


def recipes():
    shaped("nest", ["T T", "TTT"], {"T": "#thatch_straw"}, "nest", skill="husbandry")
    shaped("beehive", ["PPP", "T T", "PPP"], {"P": "#planks", "T": "thatch"}, "beehive", skill="husbandry", level=1)
    process("shears", "anvil", [("iron_ingot", 2)], [("shears", 1)], "smithing", 8, tool="hammer", energy=("heat", 1))
    process("jug", "furnace", [("clay_ball", 2)], [("jug", 1)], "pottery", 6, energy=("heat", 1))
    process("cheese", "campfire", [("milk", 3)], [("cheese", 1), ("jug", 3)], "cooking", 15, level=1, energy=("heat", 1))
    process("cooked_egg", "campfire", [("egg", 1)], [("cooked_egg", 1)], "cooking", 3, energy=("heat", 0.5))
    for raw, cooked in (("mutton", "cooked_mutton"), ("chicken", "cooked_chicken")):
        process(cooked, "campfire", [(raw, 1)], [(cooked, 1)], "cooking", 6, energy=("heat", 1))
    process("string_from_fleece", "loom", [("fleece", 1)], [("string", 4)], "weaving", 6)
    process("white_wool_from_fleece", "loom", [("fleece", 2)], [("white_wool", 1)], "weaving", 8)
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
