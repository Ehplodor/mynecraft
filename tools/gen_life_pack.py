"""Original textures, models and blockstates of the blocks added for M27..M34 (stations, crops,
building, energy, art), and the French names of the new blocks and items.

Usage: python3 tools/gen_life_pack.py   (after gen_default_pack.py; writes only the new files)
"""
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_default_pack import NS, S, clamp, noisy, rgba, save, write_json  # noqa: E402


def wood(x, y, r, base=(160, 125, 75)):
    g = r.randint(-8, 8) - (30 if y % 4 == 3 else 0)
    return (clamp(base[0] + g), clamp(base[1] + g), clamp(base[2] + g), 255)


def stone(x, y, r, v=118):
    v = clamp(v + r.randint(-14, 14))
    return (v, v, v + 4, 255)


def cube(name, up, down, side, front=None, cutout=False):
    """A full block: top, bottom, sides (front on the north face)."""
    write_json(f"models/block/{name}.json", {
        "parent": "block/cube",
        "textures": {"particle": f"block/{side}", "up": f"block/{up}", "down": f"block/{down}",
                     "north": f"block/{front or side}", "south": f"block/{side}",
                     "east": f"block/{side}", "west": f"block/{side}"}})


def state(name, variants=None):
    write_json(f"blockstates/{name}.json", {"variants": variants or {"": {"model": f"minecraft:block/{name}"}}})


def cross(name, texture):
    write_json(f"models/block/{name}.json", {"parent": "block/cross", "textures": {"cross": f"block/{texture}"}})


def main():
    # ---- stations
    def fire(x, y, r):
        d = abs(x - 7.5) + (15 - y) * 0.5
        if y >= 12:
            return stone(x, y, r, 100) if (x + y) % 3 else (70, 70, 72, 255)
        if y >= 9 and 2 <= x <= 13:
            return wood(x, y, r, (100, 70, 40))
        if d < 7 + r.random() * 2 and y >= 2:
            return (255, clamp(120 + (15 - y) * 6 + r.randint(-20, 20)), 30, 255)
        return (0, 0, 0, 0)
    save("campfire", rgba(fire, 101))
    save("campfire_top", rgba(lambda x, y, r: (255, clamp(150 + r.randint(-40, 60)), 40, 255)
                              if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 14 else wood(x, y, r, (100, 70, 40))
                              if abs(x - y) < 2 or abs(x + y - 15) < 2 else stone(x, y, r, 95), 102))
    cube("campfire", "campfire_top", "campfire_top", "campfire")
    write_json("models/block/campfire.json", {"parent": "block/block", "textures": {"particle": "block/campfire"},
               "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                   "up": {"texture": "block/campfire_top"}, "down": {"texture": "block/campfire_top", "cullface": "down"},
                   **{f: {"texture": "block/campfire", "cullface": f} for f in ("north", "south", "east", "west")}}}]})
    save("furnace_side", rgba(lambda x, y, r: stone(x, y, r, 105 - (25 if x % 8 == 0 or y % 5 == 0 else 0)), 103))
    save("furnace_front", rgba(lambda x, y, r: (clamp(230 + r.randint(-20, 20)), clamp(110 + r.randint(-30, 30)), 20, 255)
                               if 4 <= x <= 11 and 8 <= y <= 13 else (30, 30, 32, 255) if 3 <= x <= 12 and 7 <= y <= 14
                               else stone(x, y, r, 105 - (25 if x % 8 == 0 or y % 5 == 0 else 0)), 104))
    save("furnace_top", noisy((95, 95, 98), 12, 105))
    cube("furnace", "furnace_top", "furnace_top", "furnace_side", "furnace_front")
    # ---- crops and wild food
    save("farmland", rgba(lambda x, y, r: (lambda g: (clamp(82 + g), clamp(56 + g), clamp(36 + g), 255))(
        r.randint(-8, 8) - (25 if y % 4 == 0 else 0)), 106))
    cube("farmland", "farmland", "dirt", "dirt")
    for i, (age, ripe) in enumerate(((0, 0.0), (2, 0.3), (5, 0.6), (7, 1.0))):
        h = 4 + i * 3
        def wheat(x, y, r, h=h, ripe=ripe):
            if x % 3 == 1 or y < 15 - h:
                return (0, 0, 0, 0)
            top = y < 15 - h + 3 and ripe > 0.5
            g = (int(80 + 140 * ripe), int(150 + 50 * ripe), int(40 + 20 * ripe))
            return (clamp(g[0] + (30 if top else 0) + r.randint(-15, 15)), clamp(g[1] + r.randint(-15, 15)), g[2], 255)
        save(f"wheat_stage{age}", rgba(wheat, 107 + i))
        cross(f"wheat_stage{age}", f"wheat_stage{age}")
    state("wheat", {f"age={a}": {"model": f"minecraft:block/wheat_stage{a}"} for a in (0, 2, 5, 7)})
    def bush(berries):
        def px(x, y, r):
            d = (x - 7.5) ** 2 + (y - 9) ** 2
            if d > 42 or y < 3:
                return (0, 0, 0, 0)
            if berries and r.random() < 0.12:
                return (200, 25, 45, 255)
            return (30, clamp(100 + r.randint(-30, 30)), 35, 255) if r.random() > 0.15 else (0, 0, 0, 0)
        return px
    save("sweet_berry_bush_stage3", rgba(bush(True), 112))
    save("sweet_berry_bush_stage1", rgba(bush(False), 112))
    cross("sweet_berry_bush_stage3", "sweet_berry_bush_stage3")
    cross("sweet_berry_bush_stage1", "sweet_berry_bush_stage1")
    state("sweet_berry_bush", {"age=3": {"model": "minecraft:block/sweet_berry_bush_stage3"},
                               "age=1": {"model": "minecraft:block/sweet_berry_bush_stage1"}})
    save("brown_mushroom", rgba(lambda x, y, r: (clamp(150 + r.randint(-15, 15)), clamp(105 + r.randint(-10, 10)), 70, 255)
                                if (x - 7.5) ** 2 / 25 + (y - 8) ** 2 / 9 < 1 and y <= 9
                                else (220, 210, 190, 255) if 6 <= x <= 9 and 9 < y <= 14 else (0, 0, 0, 0), 113))
    cross("brown_mushroom", "brown_mushroom")
    state("brown_mushroom")
    save("oak_sapling", rgba(lambda x, y, r: (100, 75, 45, 255) if x in (7, 8) and y >= 9
                             else (40, clamp(120 + r.randint(-30, 30)), 40, 255)
                             if (x - 7.5) ** 2 + (y - 6) ** 2 < 22 and r.random() > 0.2 else (0, 0, 0, 0), 114))
    cross("oak_sapling", "oak_sapling")
    state("oak_sapling")
    save("clay", noisy((160, 165, 178), 8, 115))
    # ---- building
    def door(open_):
        def px(x, y, r):
            frame = x in (0, 1, 14, 15) or y in (0, 1)
            if frame:
                return wood(x, y, r, (120, 90, 52))
            if open_:
                return (0, 0, 0, 0)
            if 4 <= x <= 11 and 3 <= y <= 6:
                return (200, 225, 235, 140)
            if x == 12 and y == 9:
                return (60, 60, 60, 255)
            return wood(x, y, r)
        return px
    save("oak_door", rgba(door(False), 116))
    save("oak_door_open", rgba(door(True), 116))
    for n in ("oak_door", "oak_door_open"):
        write_json(f"models/block/{n}.json", {"parent": "block/cube_all", "textures": {"all": f"block/{n}"}})
    state("oak_door", {"open=false": {"model": "minecraft:block/oak_door"},
                       "open=true": {"model": "minecraft:block/oak_door_open"}})
    save("oak_fence", rgba(lambda x, y, r: wood(x, y, r) if x in (6, 7, 8, 9) or y in (3, 4, 10, 11) else (0, 0, 0, 0), 117))
    save("thatch", rgba(lambda x, y, r: (clamp(200 + r.randint(-30, 20)), clamp(165 + r.randint(-30, 20)), 70, 255)
                        if (x + r.randint(0, 2)) % 3 else (150, 120, 50, 255), 118))
    save("straw_bed_top", rgba(lambda x, y, r: (clamp(170 + r.randint(-15, 15)), 40, 40, 255) if y >= 5
                               else (230, 225, 210, 255), 119))
    save("straw_bed_side", rgba(lambda x, y, r: wood(x, y, r) if y >= 10 else
                                (clamp(170 + r.randint(-15, 15)), 40, 40, 255), 120))
    cube("straw_bed", "straw_bed_top", "oak_planks", "straw_bed_side")
    # ---- workshops
    save("composter", rgba(lambda x, y, r: wood(x, y, r, (130, 95, 55)) if x % 5 else (80, 60, 35, 255), 121))
    save("composter_top", rgba(lambda x, y, r: wood(x, y, r, (130, 95, 55)) if x in (0, 15) or y in (0, 15)
                               else (clamp(70 + r.randint(-15, 15)), clamp(55 + r.randint(-10, 10)), 30, 255), 122))
    cube("composter", "composter_top", "oak_planks", "composter")
    save("millstone_top", rgba(lambda x, y, r: (50, 50, 50, 255) if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 2
                               else stone(x, y, r, 150 - (30 if int(math.atan2(y - 7.5, x - 7.5) * 4) % 2 else 0)), 123))
    save("millstone_side", rgba(lambda x, y, r: stone(x, y, r, 140 - (40 if y in (7, 8) else 0)), 124))
    cube("millstone", "millstone_top", "millstone_side", "millstone_side")
    save("anvil", rgba(lambda x, y, r: (lambda v: (v, v, v + 6, 255))(clamp(62 + r.randint(-8, 8) - (15 if y % 6 == 0 else 0))), 125))
    cube("anvil", "anvil", "anvil", "anvil")
    save("tanning_vat_top", rgba(lambda x, y, r: wood(x, y, r, (110, 80, 45)) if x in (0, 1, 14, 15) or y in (0, 1, 14, 15)
                                 else (clamp(110 + r.randint(-10, 10)), clamp(70 + r.randint(-10, 10)), 35, 255), 126))
    save("tanning_vat_side", rgba(lambda x, y, r: wood(x, y, r, (110, 80, 45)) if y % 7 else (60, 60, 60, 255), 127))
    cube("tanning_vat", "tanning_vat_top", "oak_planks", "tanning_vat_side")
    save("loom", rgba(lambda x, y, r: (230, 225, 210, 255) if 3 <= x <= 12 and 2 <= y <= 11 and x % 2 == 0
                      else wood(x, y, r), 128))
    cube("loom", "oak_planks", "oak_planks", "loom")
    save("potters_wheel_top", rgba(lambda x, y, r: (clamp(150 + r.randint(-10, 10)), 95, 70, 255)
                                   if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 30 else wood(x, y, r), 129))
    cube("potters_wheel", "potters_wheel_top", "oak_planks", "oak_planks")
    def wheel(x, y, r):
        a, d = math.atan2(y - 7.5, x - 7.5), math.hypot(x - 7.5, y - 7.5)
        if d > 7.6:
            return wood(x, y, r, (90, 65, 38))
        if d > 6.2 or abs(math.sin(a * 4)) < 0.22 or d < 1.5:
            return wood(x, y, r, (140, 105, 60))
        return (0, 0, 0, 0) if d < 6.2 else wood(x, y, r)
    save("water_wheel", rgba(wheel, 130))
    write_json("models/block/water_wheel.json", {"parent": "block/cube_all", "textures": {"all": "block/water_wheel"}})
    def sails(x, y, r):
        a = math.atan2(y - 7.5, x - 7.5)
        if math.hypot(x - 7.5, y - 7.5) < 1.5:
            return (90, 65, 38, 255)
        return (235, 230, 215, 255) if (a * 2 / math.pi) % 1 < 0.35 else wood(x, y, r, (120, 90, 50))
    save("windmill", rgba(sails, 131))
    cube("windmill", "oak_planks", "oak_planks", "oak_planks", "windmill")
    save("sawmill", rgba(lambda x, y, r: (190, 190, 200, 255) if (x - 7.5) ** 2 + (y - 4) ** 2 < 12 and (x + y) % 2
                         else wood(x, y, r), 132))
    cube("sawmill", "sawmill", "oak_planks", "oak_planks", "sawmill")
    colors = {"red": (180, 40, 40), "blue": (45, 70, 170), "white": (230, 230, 225), "yellow": (225, 195, 50)}
    save("dye_vat_top", rgba(lambda x, y, r: wood(x, y, r) if x in (0, 15) or y in (0, 15)
                             else list(colors.values())[(x // 4 + y // 4) % 4] + (255,), 133))
    cube("dye_vat", "dye_vat_top", "oak_planks", "tanning_vat_side")
    # ---- art and decoration
    for i, (c, rgb) in enumerate(colors.items()):
        save(f"{c}_wool", noisy(rgb, 10, 140 + i))
        write_json(f"models/block/{c}_wool.json", {"parent": "block/cube_all", "textures": {"all": f"block/{c}_wool"}})
        state(f"{c}_wool")
    save("terracotta", noisy((160, 90, 60), 8, 150))
    save("decorated_pot", rgba(lambda x, y, r: (40, 30, 25, 255) if y in (4, 11) or (y in range(6, 10) and (x + y) % 4 == 0)
                               else (clamp(165 + r.randint(-8, 8)), 92, 62, 255), 151))
    save("painting", rgba(lambda x, y, r: wood(x, y, r, (110, 75, 40)) if x in (0, 15) or y in (0, 15)
                          else (90, 160, 230, 255) if y < 6 else (240, 200, 60, 255) if (x - 11) ** 2 + (y - 3) ** 2 < 4
                          else (60, 140, 60, 255) if y < 11 else (70, 110, 180, 255), 152))
    save("statue", rgba(lambda x, y, r: (lambda v: (v, v, v - 4, 255))(clamp(220 + r.randint(-8, 8)) -
                        (50 if ((x - 7.5) ** 2 + (y - 3.5) ** 2 < 6 or (5 <= x <= 10 and 6 <= y <= 13)) else 0)), 153))
    for n in ("clay", "thatch", "oak_fence", "terracotta", "decorated_pot", "statue", "anvil"):
        if n != "anvil":
            write_json(f"models/block/{n}.json", {"parent": "block/cube_all", "textures": {"all": f"block/{n}"}})
    cube("painting", "oak_planks", "oak_planks", "oak_planks", "painting")
    for n in ("campfire", "furnace", "farmland", "clay", "oak_fence", "thatch", "straw_bed", "composter", "millstone",
              "anvil", "tanning_vat", "loom", "potters_wheel", "water_wheel", "windmill", "terracotta", "decorated_pot",
              "painting", "statue", "sawmill", "dye_vat"):
        state(n)
    # ---- names
    path = os.path.join(NS, "lang", "fr_fr.json")
    with open(path, encoding="utf-8") as f:
        lang = json.load(f)
    blocks = {"campfire": "Feu de camp", "furnace": "Four", "farmland": "Terre labourée", "wheat": "Blé",
              "sweet_berry_bush": "Buisson de baies", "brown_mushroom": "Champignon", "clay": "Argile",
              "oak_sapling": "Pousse de chêne", "oak_door": "Porte en chêne", "oak_fence": "Barrière en chêne",
              "thatch": "Chaume", "straw_bed": "Lit de paille", "composter": "Composteur", "millstone": "Meule",
              "anvil": "Enclume", "tanning_vat": "Cuve de tannage", "loom": "Métier à tisser", "potters_wheel": "Tour de potier",
              "water_wheel": "Roue à aubes", "windmill": "Moulin à vent", "red_wool": "Drap rouge", "blue_wool": "Drap bleu",
              "white_wool": "Drap blanc", "yellow_wool": "Drap jaune", "terracotta": "Terre cuite",
              "decorated_pot": "Pot décoré", "painting": "Tableau", "statue": "Statue", "sawmill": "Scierie",
              "dye_vat": "Cuve de teinture"}
    items = {"wooden_pickaxe": "Pioche en bois", "stone_pickaxe": "Pioche en pierre", "iron_pickaxe": "Pioche en fer",
             "wooden_axe": "Hache en bois", "stone_axe": "Hache en pierre", "iron_axe": "Hache en fer",
             "wooden_shovel": "Pelle en bois", "stone_shovel": "Pelle en pierre", "iron_shovel": "Pelle en fer",
             "wooden_hoe": "Houe en bois", "stone_hoe": "Houe en pierre", "iron_hoe": "Houe en fer",
             "flint_knife": "Couteau de silex", "iron_knife": "Couteau de fer", "fishing_rod": "Canne à pêche",
             "bow": "Arc", "arrow": "Flèche", "hammer": "Marteau", "chisel": "Ciseau", "flute": "Flûte",
             "sweet_berries": "Baies", "wild_herbs": "Herbes sauvages", "wheat": "Blé", "wheat_seeds": "Graines de blé",
             "bread": "Pain", "flour": "Farine", "venison": "Venaison crue", "cooked_venison": "Venaison rôtie",
             "rabbit": "Lapin cru", "cooked_rabbit": "Lapin rôti", "trout": "Truite crue", "cooked_trout": "Truite grillée",
             "smoked_meat": "Viande fumée", "roasted_mushroom": "Champignon grillé", "herb_trout": "Truite aux herbes",
             "berry_tart": "Tarte aux baies", "charcoal": "Charbon de bois", "iron_ingot": "Lingot de fer",
             "hide": "Peau", "leather": "Cuir", "plant_fiber": "Fibre végétale", "string": "Ficelle", "cloth": "Toile",
             "clay_ball": "Boule d'argile", "ash": "Cendre", "compost": "Compost", "slag": "Scories",
             "red_dye": "Teinture rouge", "blue_dye": "Teinture bleue", "white_dye": "Teinture blanche",
             "yellow_dye": "Teinture jaune", "canvas": "Toile à peindre"}
    for k, v in blocks.items():
        lang["block.minecraft." + k] = v
    for k, v in items.items():
        lang["item.minecraft." + k] = v
    with open(path, "w", encoding="utf-8") as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write("\n")


DATA = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "defaultdata")


def data(rel, obj):
    path = os.path.join(DATA, "data", "minecraft", rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def shaped(name, pattern, key, result, count=1, **rules):
    r = {"type": "minecraft:crafting_shaped", "pattern": pattern,
         "key": {k: ("#minecraft:" + v[1:] if v.startswith("#") else "minecraft:" + v) for k, v in key.items()},
         "result": {"id": "minecraft:" + result, "count": count}}
    for k, v in rules.items():
        r["mynecraft:" + k] = v
    data(f"recipe/{name}.json", r)


def process(name, station, inputs, results, skill, seconds, level=0, tool=None, energy=None, waste=None):
    r = {"type": "mynecraft:process", "station": station,
         "ingredients": [({"tag": "minecraft:" + i[1:]} if i.startswith("#") else {"item": "minecraft:" + i}) | {"count": n}
                         for i, n in inputs],
         "skill": skill, "level": level, "seconds": seconds,
         "results": [{"id": "minecraft:" + i, "count": n} for i, n in results]}
    if tool:
        r["tool"] = tool
    if energy:
        r["energy"] = {"kind": energy[0], "amount": energy[1]}
    if waste:
        r["waste"] = [{"matter": m, "amount": a} for m, a in waste]
    data(f"recipe/{name}.json", r)


def gen_data():
    data("tags/item/stone_tool_materials.json", {"values": ["minecraft:cobblestone", "minecraft:andesite", "minecraft:granite",
                                                            "minecraft:diorite", "minecraft:deepslate"]})
    data("tags/item/compostable.json", {"values": ["minecraft:plant_fiber", "minecraft:wild_herbs", "minecraft:wheat_seeds",
                                                   "minecraft:sweet_berries", "minecraft:apple", "minecraft:oak_sapling"]})
    data("tags/item/thatch_straw.json", {"values": ["minecraft:plant_fiber", "minecraft:wheat"]})
    # Tools: wood and stone at the table, iron forged at the anvil.
    shapes = {"pickaxe": ["MMM", " S ", " S "], "axe": ["MM", "MS", " S"], "shovel": ["M", "S", "S"], "hoe": ["MM", " S", " S"]}
    for kind, pattern in shapes.items():
        shaped(f"wooden_{kind}", pattern, {"M": "#planks", "S": "stick"}, f"wooden_{kind}")
        shaped(f"stone_{kind}", pattern, {"M": "#stone_tool_materials", "S": "stick"}, f"stone_{kind}", skill="crafting", level=1)
        process(f"iron_{kind}", "anvil", [("iron_ingot", {"pickaxe": 3, "axe": 3, "shovel": 1, "hoe": 2}[kind]), ("stick", 2)],
                [(f"iron_{kind}", 1)], "smithing", 12, level=1, tool="hammer", energy=("heat", 1))
    shaped("flint_knife", ["F", "S"], {"F": "flint", "S": "stick"}, "flint_knife")
    shaped("arrow", ["F", "S", "P"], {"F": "flint", "S": "stick", "P": "plant_fiber"}, "arrow", 4, skill="hunting")
    shaped("string", ["PPP"], {"P": "plant_fiber"}, "string")
    shaped("fishing_rod", ["  S", " SF", "S F"], {"S": "stick", "F": "string"}, "fishing_rod", skill="fishing")
    shaped("bow", [" SF", "S F", " SF"], {"S": "stick", "F": "string"}, "bow", skill="hunting", level=1)
    shaped("hammer", [" I ", " SI", "S  "], {"I": "iron_ingot", "S": "stick"}, "hammer", skill="smithing")
    process("iron_knife", "anvil", [("iron_ingot", 1), ("stick", 1)], [("iron_knife", 1)], "smithing", 8, tool="hammer", energy=("heat", 1))
    process("chisel", "anvil", [("iron_ingot", 2), ("stick", 1)], [("chisel", 1)], "smithing", 8, level=1, tool="hammer", energy=("heat", 1))
    # Stations and building.
    shaped("campfire", [" S ", "SFS", "CCC"], {"S": "stick", "F": "flint", "C": "cobblestone"}, "campfire")
    shaped("furnace", ["CCC", "C C", "CCC"], {"C": "cobblestone"}, "furnace")
    shaped("composter", ["P P", "P P", "PPP"], {"P": "#planks"}, "composter")
    shaped("millstone", [" C ", "CLC", " C "], {"C": "cobblestone", "L": "#logs"}, "millstone", skill="building", level=1)
    shaped("anvil", ["III", " I ", "III"], {"I": "iron_ingot"}, "anvil", skill="smithing", level=1)
    shaped("tanning_vat", ["P P", "PCP", "PPP"], {"P": "#planks", "C": "cobblestone"}, "tanning_vat")
    shaped("loom", ["FF", "PP"], {"F": "string", "P": "#planks"}, "loom")
    shaped("potters_wheel", ["KKK", "SPS"], {"K": "clay_ball", "S": "stick", "P": "#planks"}, "potters_wheel")
    shaped("dye_vat", ["P P", "PKP", "PPP"], {"P": "#planks", "K": "clay_ball"}, "dye_vat")
    shaped("water_wheel", ["SPS", "PLP", "SPS"], {"S": "stick", "P": "#planks", "L": "#logs"}, "water_wheel", skill="building", level=2)
    shaped("windmill", ["FSF", "SLS", "FSF"], {"F": "cloth", "S": "stick", "L": "#logs"}, "windmill", skill="building", level=2)
    shaped("sawmill", ["III", "PLP"], {"I": "iron_ingot", "P": "#planks", "L": "#logs"}, "sawmill", skill="building", level=2)
    shaped("oak_door", ["PP", "PP", "PP"], {"P": "oak_planks"}, "oak_door", 3, skill="building")
    shaped("oak_fence", ["PSP", "PSP"], {"P": "oak_planks", "S": "stick"}, "oak_fence", 3, skill="building")
    shaped("thatch", ["FF", "FF"], {"F": "#thatch_straw"}, "thatch", skill="building")
    shaped("straw_bed", ["TTT", "PPP"], {"T": "thatch", "P": "#planks"}, "straw_bed")
    # Cooking at the campfire (heat from fuel in the bag: sticks, planks, logs, coal).
    for raw, cooked in (("venison", "cooked_venison"), ("rabbit", "cooked_rabbit"), ("trout", "cooked_trout")):
        process(cooked, "campfire", [(raw, 1)], [(cooked, 1)], "cooking", 6, energy=("heat", 1))
    process("roasted_mushroom", "campfire", [("brown_mushroom", 1)], [("roasted_mushroom", 1)], "cooking", 3, energy=("heat", 0.5))
    process("herb_trout", "campfire", [("trout", 1), ("wild_herbs", 1)], [("herb_trout", 1)], "cooking", 8, level=2, energy=("heat", 1))
    process("smoked_meat", "campfire", [("venison", 2)], [("smoked_meat", 2)], "cooking", 20, level=1, energy=("heat", 3))
    process("bread", "campfire", [("flour", 2)], [("bread", 1)], "cooking", 10, level=1, energy=("heat", 2))
    process("berry_tart", "furnace", [("flour", 2), ("sweet_berries", 3)], [("berry_tart", 1)], "cooking", 12, level=3, energy=("heat", 2))
    # The furnace: smelting, glass, terracotta, charcoal.
    process("iron_ingot", "furnace", [("iron_ore", 1)], [("iron_ingot", 1)], "smithing", 10, energy=("heat", 2), waste=[("slag", 0.3)])
    process("glass", "furnace", [("sand", 1)], [("glass", 1)], "glasswork", 6, energy=("heat", 1))
    process("smooth_stone", "furnace", [("cobblestone", 1)], [("stone", 1)], "mining", 6, energy=("heat", 1))
    process("terracotta", "furnace", [("clay_ball", 4)], [("terracotta", 1)], "pottery", 8, energy=("heat", 2))
    process("charcoal", "furnace", [("#logs", 1)], [("charcoal", 1)], "woodcutting", 8, energy=("heat", 1), waste=[("ash", 0.2)])
    # Composting returns plant matter to the soil.
    process("compost", "composter", [("#compostable", 4)], [("compost", 1)], "farming", 10)
    # The millstone and the sawmill: work from a wheel or a windmill nearby, else the arms (3x slower, tiring).
    process("flour", "millstone", [("wheat", 2)], [("flour", 1)], "milling", 6, energy=("work", 2))
    process("gravel", "millstone", [("cobblestone", 1)], [("gravel", 1)], "milling", 6, energy=("work", 3))
    process("sand", "millstone", [("gravel", 1)], [("sand", 1)], "milling", 6, energy=("work", 3))
    for w in ("oak", "birch", "spruce"):
        process(f"{w}_planks_sawn", "sawmill", [(f"{w}_log", 1)], [(f"{w}_planks", 6)], "woodcutting", 4, energy=("work", 3),
                waste=[("plants", 0.5)])
    # Tannery: hides into leather, waste water to the river.
    process("leather", "tanning_vat", [("hide", 1)], [("leather", 1)], "tanning", 30, waste=[("wastewater", 1)])
    # Weaving, dyeing, pottery, art, music.
    process("cloth", "loom", [("string", 4)], [("cloth", 1)], "weaving", 10)
    process("white_wool", "loom", [("cloth", 2)], [("white_wool", 1)], "weaving", 8)
    process("canvas", "loom", [("cloth", 2), ("stick", 2)], [("canvas", 1)], "weaving", 8, level=1)
    for flower, dye in (("poppy", "red_dye"), ("gentian", "blue_dye"), ("edelweiss", "white_dye"), ("wild_herbs", "yellow_dye")):
        process(dye, "dye_vat", [(flower, 1)], [(dye, 2)], "dyeing", 6, waste=[("wastewater", 0.2)])
    for c in ("red", "blue", "yellow"):
        process(f"{c}_wool", "dye_vat", [("white_wool", 1), (f"{c}_dye", 1)], [(f"{c}_wool", 1)], "dyeing", 6, waste=[("wastewater", 0.1)])
    process("decorated_pot", "potters_wheel", [("terracotta", 1), ("#dyes", 1)], [("decorated_pot", 1)], "pottery", 15, level=1)
    data("tags/item/dyes.json", {"values": ["minecraft:red_dye", "minecraft:blue_dye", "minecraft:white_dye", "minecraft:yellow_dye"]})
    process("painting", "crafting_table", [("canvas", 1), ("red_dye", 1), ("blue_dye", 1), ("yellow_dye", 1)], [("painting", 1)],
            "art", 20, level=1)
    process("statue", "crafting_table", [("stone", 2)], [("statue", 1)], "art", 30, level=2, tool="chisel")
    process("flute", "crafting_table", [("stick", 1)], [("flute", 1)], "music", 8, tool="knife")
    files = []
    for root, _, names in os.walk(DATA):
        for n in names:
            if n != "index.txt":
                files.append(os.path.relpath(os.path.join(root, n), DATA).replace(os.sep, "/"))
    with open(os.path.join(DATA, "index.txt"), "w") as f:
        f.write("\n".join(sorted(files)) + "\n")


if __name__ == "__main__":
    main()
    gen_data()
