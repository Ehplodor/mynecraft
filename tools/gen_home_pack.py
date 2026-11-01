"""The home: the two-block oak door (lower and upper halves), the furniture (straw bed, table, chair,
lantern lit and out, water barrel, rug): textures, models, blockstates, French names and recipes.

Usage: python3 tools/gen_home_pack.py   (after gen_default_pack.py, gen_life_pack.py and gen_herd_pack.py;
writes only its own files, and replaces the old one-block door's)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_default_pack import NS, clamp, rgba, save, write_json  # noqa: E402
from gen_life_pack import DATA, shaped, wood  # noqa: E402

IRON = (58, 58, 64, 255)
CLEAR = (0, 0, 0, 0)


def cube(name, up, down, side):
    write_json(f"models/block/{name}.json", {
        "parent": "block/cube",
        "textures": {"particle": f"block/{side}", "up": f"block/{up}", "down": f"block/{down}", "north": f"block/{side}",
                     "south": f"block/{side}", "east": f"block/{side}", "west": f"block/{side}"}})


def door():
    """A cottage door: vertical boards, a frame, iron hinge straps, a brace; a little window up top."""
    def board(x, y, r, base=(150, 112, 66)):
        g = r.randint(-7, 7) - (24 if x in (5, 10) else 0)  # the grooves between boards
        return (clamp(base[0] + g), clamp(base[1] + g), clamp(base[2] + g), 255)

    def lower(x, y, r):
        if x in (0, 15) or y == 15:
            return wood(x, y, r, (112, 82, 48))
        if y in (3, 12) and x <= 7:
            return IRON  # hinge straps
        if 12 <= x <= 13 and 1 <= y <= 3:
            return (40, 40, 44, 255) if x == 13 or y == 2 else IRON  # the latch
        if abs((x - 1) - (14 - y) * 13 / 13) < 1.1 and 4 <= y <= 11:
            return wood(x, y, r, (124, 92, 54))  # the brace
        return board(x, y, r)

    def upper(x, y, r):
        if x in (0, 15) or y == 0:
            return wood(x, y, r, (112, 82, 48))
        if 4 <= x <= 11 and 3 <= y <= 9:
            if x in (4, 11) or y in (3, 9):
                return wood(x, y, r, (112, 82, 48))
            return wood(x, y, r, (112, 82, 48)) if x in (7, 8) or y == 6 else CLEAR  # glazing bars, open lights
        if y == 12 and x <= 7:
            return IRON
        return board(x, y, r)

    save("oak_door_bottom", rgba(lower, 501))
    save("oak_door_top", rgba(upper, 502))
    for n in ("oak_door_bottom", "oak_door_top"):
        write_json(f"models/block/{n}.json", {"parent": "block/cube_all", "textures": {"all": f"block/{n}"}})
    variants = {}
    for half in ("lower", "upper"):
        for axis in ("x", "z"):
            for open_ in ("false", "true"):
                variants[f"half={half},axis={axis},open={open_}"] = {
                    "model": "minecraft:block/oak_door_" + ("bottom" if half == "lower" else "top")}
    write_json("blockstates/oak_door.json", {"variants": variants})
    for old in ("models/block/oak_door.json", "models/block/oak_door_open.json", "textures/block/oak_door.png",
                "textures/block/oak_door_open.png"):
        p = os.path.join(NS, old)
        if os.path.exists(p):
            os.remove(p)


def furniture():
    # The straw bed: a red blanket with a white pillow at the head, over a ticking of straw.
    def bed_top(x, y, r):
        if y <= 4:
            return (clamp(232 + r.randint(-6, 6)), clamp(228 + r.randint(-6, 6)), clamp(214 + r.randint(-6, 6)), 255)
        if y == 5:
            return (235, 225, 200, 255)  # the turned-down sheet
        red = 168 + r.randint(-12, 12) - (28 if (x // 3 + y // 3) % 2 else 0)
        return (clamp(red), 42, 40, 255)
    save("straw_bed_top", rgba(bed_top, 511))
    save("straw_bed_side", rgba(lambda x, y, r: (clamp(160 + r.randint(-12, 12)), 40, 38, 255) if y < 9 else
                                (clamp(205 + r.randint(-20, 15)), clamp(178 + r.randint(-20, 15)), 96, 255), 512))
    cube("straw_bed", "straw_bed_top", "oak_planks", "straw_bed_side")

    # The table: a top of planks with a darker edge.
    save("oak_table_top", rgba(lambda x, y, r: wood(x, y, r, (128, 94, 56)) if x in (0, 15) or y in (0, 15)
                               else wood(x, y, r, (166, 128, 80)), 513))
    cube("oak_table", "oak_table_top", "oak_planks", "oak_planks")
    write_json("blockstates/oak_table.json", {"variants": {"": {"model": "minecraft:block/oak_table"}}})

    # The chair: planks a shade warmer than the floor's.
    save("oak_chair", rgba(lambda x, y, r: wood(x, y, r, (170, 126, 74)), 514))
    write_json("models/block/oak_chair.json", {"parent": "block/cube_all", "textures": {"all": "block/oak_chair"}})
    write_json("blockstates/oak_chair.json", {"variants": {f"facing={f}": {"model": "minecraft:block/oak_chair"}
                                                           for f in ("north", "south", "west", "east")}})

    # The lantern: an iron frame round a warm flame (sides seen through columns 5..10, rows 9..15).
    def lantern(lit):
        def px(x, y, r):
            if x in (5, 10) or y in (9, 15) or y < 9 or x < 5 or x > 10:
                return IRON
            if lit:
                d = abs(x - 7.5) + abs(y - 12)
                return (255, clamp(225 - int(d * 18) + r.randint(-10, 10)), clamp(120 - int(d * 25)), 255)
            return (70, 72, 70, 255)
        return px
    save("lantern", rgba(lantern(True), 515))
    save("lantern_off", rgba(lantern(False), 516))
    save("lantern_top", rgba(lambda x, y, r: IRON, 517))
    cube("lantern", "lantern_top", "lantern_top", "lantern")
    cube("lantern_off", "lantern_top", "lantern_top", "lantern_off")
    write_json("blockstates/lantern.json", {"variants": {"lit=true": {"model": "minecraft:block/lantern"},
                                                         "lit=false": {"model": "minecraft:block/lantern_off"}}})

    # The barrel: staves bound by two iron hoops, a round lid.
    save("barrel_side", rgba(lambda x, y, r: IRON if y in (2, 13) else
                             wood(x, y, r, (138, 98, 56) if x % 4 else (110, 78, 44)), 518))
    def lid(x, y, r):
        d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
        if d > 7.2:
            return wood(x, y, r, (110, 78, 44))
        if 6.2 < d <= 7.2:
            return IRON
        return wood(x, y, r, (150, 110, 64) if (x // 4) % 2 else (136, 100, 58))
    save("barrel_top", rgba(lid, 519))
    cube("barrel", "barrel_top", "barrel_top", "barrel_side")
    write_json("blockstates/barrel.json", {"variants": {"": {"model": "minecraft:block/barrel"}}})

    # The rug: red wool, a yellow border and a diamond in the middle.
    def rug(x, y, r):
        if x in (0, 15) or y in (0, 15):
            return (120, 30, 30, 255)
        if x in (1, 14) or y in (1, 14):
            return (215, 170, 50, 255)
        if abs(x - 7.5) + abs(y - 7.5) < 4:
            return (215, 170, 50, 255) if abs(x - 7.5) + abs(y - 7.5) > 2.5 else (40, 60, 120, 255)
        return (clamp(170 + r.randint(-10, 10)), 40, 40, 255)
    save("rug", rgba(rug, 520))
    write_json("models/block/rug.json", {"parent": "block/cube_all", "textures": {"all": "block/rug"}})
    write_json("blockstates/rug.json", {"variants": {"": {"model": "minecraft:block/rug"}}})


def names():
    path = os.path.join(NS, "lang", "fr_fr.json")
    with open(path, encoding="utf-8") as f:
        lang = json.load(f)
    for k, v in {"oak_table": "Table en chêne", "oak_chair": "Chaise en chêne", "lantern": "Lanterne",
                 "barrel": "Tonneau d'eau", "rug": "Tapis", "straw_bed": "Lit de paille"}.items():
        lang["block.minecraft." + k] = v
    with open(path, "w", encoding="utf-8") as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write("\n")


def recipes():
    shaped("oak_table", ["PPP", "S S", "S S"], {"P": "#planks", "S": "stick"}, "oak_table", skill="building")
    shaped("oak_chair", ["P  ", "PPP", "S S"], {"P": "#planks", "S": "stick"}, "oak_chair", 2, skill="building")
    shaped("lantern", [" I ", "GSG", " I "], {"I": "iron_ingot", "G": "glass", "S": "stick"}, "lantern", skill="smithing")
    shaped("barrel", ["PSP", "P P", "PSP"], {"P": "#planks", "S": "oak_slab"}, "barrel", skill="building")
    shaped("rug", ["WW"], {"W": "red_wool"}, "rug", 3, skill="weaving")
    files = []
    for root, _, ns in os.walk(DATA):
        for n in ns:
            if n != "index.txt":
                files.append(os.path.relpath(os.path.join(root, n), DATA).replace(os.sep, "/"))
    with open(os.path.join(DATA, "index.txt"), "w") as f:
        f.write("\n".join(sorted(files)) + "\n")


if __name__ == "__main__":
    door()
    furniture()
    names()
    recipes()
