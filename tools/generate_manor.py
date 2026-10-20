#!/usr/bin/env python3
"""
Generates the Fallen Manor: the overgrown country house of a forgotten family.

    python3 tools/generate_manor.py

Writes src/main/resources/data/bloodline/structure/manor.nbt (33 x 24 x 35).

Layout (the gate faces south, +z):

    y = 0..4    the cellar under the house (floor y = 0, ceiling = the house floor)
    y = 5       ground level: the grass of the grounds and the house's floor
    y = 6..9    the ground floor: library (west), great hall (middle), study (east)
    y = 10      the upper floor's floor
    y = 11..14  the upper floor: bedrooms, a collapsed corner
    y = 15..21  the roof

    z = 0       the back wall of the grounds
    z = 1..7    the walled garden with the family graveyard
    z = 8..21   the house (x = 6..26)
    z = 22..30  the front courtyard with a dry fountain
    z = 31..34  the gatehouse in the front wall (x = 13..19)

The spots the mod uses (lectern, page hiding places, steward, graves, gate sign, the usurper) are listed in
SPOTS below and must match the constants in Manors.java.
"""
import os
import random

from structure_writer import Template

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "bloodline", "structure", "manor.nbt")
SIZE = (33, 24, 35)
G = 5  # ground level

SPOTS = {
    "lectern": (16, G + 1, 11),
    "page_library": (8, G + 1, 10),
    "page_study": (24, G + 1, 10),
    "page_cellar": (24, 1, 19),
    "page_garden": (30, G + 1, 6),
    "steward": (14, G + 1, 16),
    "usurper": (7, G + 1, 26),
    "gate_sign": (16, G + 4, 34),
}
# Graves: a headstone at (x, G + 1, z), its sign in front at z + 1. Two rows of thirteen, filled west to east.
GRAVE_ROWS = (2, 5)
GRAVE_XS = range(3, 28, 2)

rng = random.Random(1347)


def pick(*options):
    return rng.choice(options)


def build():
    t = Template(SIZE)

    # --- The ground: grass over a little dirt, and a clear space above it for the grounds ---------------
    for x in range(33):
        for z in range(35):
            t.put(x, G - 2, z, "dirt")
            t.put(x, G - 1, z, "dirt")
            t.put(x, G, z, pick("grass_block", "grass_block", "grass_block", "coarse_dirt", "podzol"), snowy=False)
            for y in range(G + 1, G + 4):
                t.put(x, y, z, "air")

    # --- The wall around the grounds -----------------------------------------------------------------
    for x in range(33):
        wall(t, x, 0)
        if not 15 <= x <= 17:
            wall(t, x, 34)
    for z in range(1, 34):
        wall(t, 0, z)
        wall(t, 32, z)
    # A few gaps where the wall has fallen.
    for (x, z) in ((0, 12), (0, 13), (32, 24), (9, 0)):
        t.put(x, G + 2, z, "air")
        t.put(x, G + 1, z, pick("mossy_cobblestone", "cobblestone"))

    gatehouse(t)
    courtyard(t)
    house(t)
    garden(t)

    t.connect()
    count = t.write(OUT)
    print(f"manor: {count} blocks")


def wall(t, x, z):
    t.put(x, G + 1, z, pick("mossy_cobblestone", "cobblestone", "mossy_cobblestone", "cracked_stone_bricks"))
    t.put(x, G + 2, z, pick("mossy_cobblestone_wall", "cobblestone_wall"))


def gatehouse(t):
    # Two towers either side of the gate, joined by an arch with the gate sign on its front.
    for x0 in (13, 18):
        for x in range(x0, x0 + 2):
            for z in range(31, 34):
                for y in range(G + 1, G + 6):
                    t.put(x, y, z, pick("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks"))
            t.put(x, G + 6, 31, "stone_brick_wall")
            t.put(x, G + 6, 33, "stone_brick_wall")
    for x in range(15, 18):
        for z in range(31, 34):
            t.put(x, G + 4, z, pick("stone_bricks", "mossy_stone_bricks"))
            t.put(x, G + 5, z, pick("stone_bricks", "mossy_stone_bricks"))
        t.put(x, G + 3, 33, "stone_brick_stairs", facing="north", half="top", shape="straight", waterlogged=False)
        t.put(x, G + 3, 31, "stone_brick_stairs", facing="south", half="top", shape="straight", waterlogged=False)
    x, y, z = 16, G + 4, 34
    t.put(x, y, z, "dark_oak_wall_sign", facing="south", waterlogged=False)
    # Old iron gates, one leaf hanging open.
    t.put(15, G + 1, 32, "iron_bars")
    t.put(15, G + 2, 32, "iron_bars")
    # The path through the gate.
    for x in range(15, 18):
        for z in range(31, 35):
            t.put(x, G, z, pick("gravel", "coarse_dirt", "gravel"))


def courtyard(t):
    # A gravel drive from the gate to the fountain, around it, and on to the door.
    for z in range(29, 31):
        for x in range(15, 18):
            t.put(x, G, z, pick("gravel", "gravel", "coarse_dirt"))
    for x in range(11, 22):
        for z in range(22, 30):
            if x in (11, 12, 20, 21) or z in (22, 23, 29):
                t.put(x, G, z, pick("gravel", "gravel", "coarse_dirt", "moss_block"))
    # The dry fountain: a stone rim, mud where the water was, a cracked pillar in the middle.
    for x in range(13, 20):
        for z in range(24, 29):
            if x in (13, 19) or z in (24, 28):
                t.put(x, G + 1, z, pick("stone_brick_slab", "mossy_stone_brick_slab"), type="bottom", waterlogged=False)
            else:
                t.put(x, G, z, pick("mud", "coarse_dirt"))
    t.put(16, G + 1, 26, "chiseled_stone_bricks")
    t.put(16, G + 2, 26, "mossy_stone_brick_wall")
    # Dead bushes and grass in the courtyard.
    for _ in range(26):
        x, z = rng.randint(2, 30), rng.randint(22, 30)
        if 10 <= x <= 22 and 21 <= z <= 30:
            continue
        if t.get(x, G + 1, z) == "air":
            t.put(x, G + 1, z, pick("dead_bush", "short_grass", "short_grass", "fern"))
    # Two lantern posts by the door, long gone dark.
    for x in (13, 19):
        t.put(x, G + 1, 23, "dark_oak_fence")
        t.put(x, G + 2, 23, "dark_oak_fence")
        t.put(x, G + 3, 23, "lantern", hanging=False, waterlogged=False)


def house(t):
    x0, x1, z0, z1 = 6, 26, 8, 21
    floor_mat = ("dark_oak_planks", "spruce_planks", "dark_oak_planks")

    # The cellar: a stone box under the whole house.
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            t.put(x, 0, z, pick("cobblestone", "stone", "mossy_cobblestone"))
            for y in range(1, G):
                edge = x in (x0, x1) or z in (z0, z1)
                t.put(x, y, z, pick("cobblestone", "mossy_cobblestone", "stone") if edge else "air")
    # Pillars, wine barrels and cobwebs in the cellar.
    for (x, z) in ((12, 14), (20, 14), (12, 20)):
        for y in range(1, G):
            t.put(x, y, z, "stone_bricks")
    for x in range(8, 12):
        t.put(x, 1, 9, "barrel", facing="south", open=False)
        t.put(x, 2, 9, "barrel", facing="south", open=False)
    for (x, y, z) in ((15, 4, 12), (22, 4, 17), (9, 4, 18), (18, 3, 9)):
        t.put(x, y, z, "cobweb")
    t.put(24, 1, 19, "barrel", nbt={"id": "minecraft:barrel"}, facing="up", open=False)

    # Ground floor and upper floor.
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            t.put(x, G, z, pick(*floor_mat))
            t.put(x, G + 5, z, pick(*floor_mat))
    # Outer walls: stone below, timber frame above.
    for y in range(G + 1, G + 10):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                t.put(x, y, z, wall_block(y))
        for z in range(z0, z1 + 1):
            for x in (x0, x1):
                t.put(x, y, z, wall_block(y))
    # Corner posts.
    for x in (x0, x1):
        for z in (z0, z1):
            for y in range(G + 1, G + 10):
                t.put(x, y, z, "dark_oak_log", axis="y")
    # Air inside both floors.
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            for y in list(range(G + 1, G + 5)) + list(range(G + 6, G + 10)):
                t.put(x, y, z, "air")

    # Windows: some panes broken.
    for x in (8, 10, 22, 24):
        for z in (z0, z1):
            for y in (G + 2, G + 3, G + 7, G + 8):
                t.put(x, y, z, "glass_pane" if rng.random() < 0.6 else "air")
    for z in (11, 14, 17):
        for x in (x0, x1):
            for y in (G + 2, G + 3, G + 7, G + 8):
                t.put(x, y, z, "glass_pane" if rng.random() < 0.6 else "air")

    # The front door and the back door to the garden.
    t.put(16, G + 1, z1, "dark_oak_door", facing="north", half="lower", hinge="left", open=False, powered=False)
    t.put(16, G + 2, z1, "dark_oak_door", facing="north", half="upper", hinge="left", open=False, powered=False)
    t.put(16, G + 1, z0, "air")
    t.put(16, G + 2, z0, "air")
    # A portico over the door: stone pillars and a little roof.
    for x in range(14, 19):
        for z in (z1 + 1, z1 + 2):
            t.put(x, G, z, pick("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks"))
            t.put(x, G + 5, z, "stone_brick_slab", type="bottom", waterlogged=False)
    for x in (14, 18):
        for y in range(G + 1, G + 5):
            t.put(x, y, z1 + 2, "stone_brick_wall")
        t.put(x, G + 4, z1 + 2, "stone_bricks")
    for x in range(15, 18):
        t.put(x, G + 4, z1 + 2, "stone_brick_stairs", facing="north", half="top", shape="straight", waterlogged=False)
    t.put(16, G + 4, z1 + 1, "lantern", hanging=True, waterlogged=False)

    # Partitions: library | great hall | study, with doorways.
    for xw in (12, 20):
        for z in range(z0 + 1, z1):
            for y in range(G + 1, G + 10):
                t.put(xw, y, z, pick("stone_bricks", "stone_bricks", "mossy_stone_bricks"))
        for y in range(G + 1, G + 10):
            t.put(xw, y, z0 + 1, "dark_oak_log", axis="y")
            t.put(xw, y, z1 - 1, "dark_oak_log", axis="y")
        t.put(xw, G + 1, 17, "air")
        t.put(xw, G + 2, 17, "air")
    # The great hall is open up to the roof: no upper floor between the partitions.
    for x in range(13, 20):
        for z in range(z0 + 1, z1):
            t.put(x, G + 5, z, "air")
            for y in range(G + 10, G + 17):
                t.put(x, y, z, "air")

    # The great hall: the lectern, a long table, a cold fireplace and a chandelier.
    lx, ly, lz = SPOTS["lectern"]
    t.put(lx, ly, lz, "lectern", facing="south", has_book=False, powered=False)
    for z in range(12, z1):
        t.put(16, G + 1, z, "red_carpet")
        if z in (13, 14):
            t.put(15, G + 1, z, "red_carpet")
            t.put(17, G + 1, z, "red_carpet")
    for z in range(14, 19):
        t.put(13, G + 1, z, "dark_oak_slab", type="top", waterlogged=False)
        t.put(19, G + 1, z, "dark_oak_slab", type="top", waterlogged=False)
        if z % 2:
            t.put(14, G + 1, z, "dark_oak_stairs", facing="west", half="bottom", shape="straight", waterlogged=False)
            t.put(18, G + 1, z, "dark_oak_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
    t.put(13, G + 2, 15, "candle", candles=3, lit=False, waterlogged=False)
    t.put(19, G + 2, 17, "candle", candles=2, lit=False, waterlogged=False)
    for x in (14, 18):
        t.put(x, G + 5, z0 + 1, "black_wall_banner", facing="south")
        for z in (z0, z1):
            t.put(x, G + 7, z, "glass_pane")
            t.put(x, G + 8, z, "glass_pane" if rng.random() < 0.6 else "air")
    for x in (15, 16, 17):
        t.put(x, G + 1, z0 + 1, "stone_bricks")
        t.put(x, G + 4, z0 + 1, "stone_brick_slab", type="bottom", waterlogged=False)
    t.put(16, G + 1, z0 + 1, "campfire", lit=False, signal_fire=False, waterlogged=False, facing="south")
    t.put(15, G + 2, z0 + 1, "stone_bricks")
    t.put(17, G + 2, z0 + 1, "stone_bricks")
    for y in range(G + 7, G + 17):
        t.put(16, y, 15, "iron_chain", axis="y", waterlogged=False)
    t.put(16, G + 6, 15, "lantern", hanging=True, waterlogged=False)
    t.put(14, G + 1, 19, "cobweb")
    t.put(18, G + 4, 10, "cobweb")

    # The library: shelves along the walls, a reading chair and the first hiding place.
    for z in range(z0 + 1, z1):
        for y in range(G + 1, G + 4):
            if z != 17:
                t.put(x0 + 1, y, z, "bookshelf" if rng.random() < 0.85 else "chiseled_bookshelf")
    for x in range(x0 + 2, 12):
        for y in range(G + 1, G + 4):
            t.put(x, y, z0 + 1, "bookshelf")
    px, py, pz = SPOTS["page_library"]
    t.put(px, py, pz, "chest", nbt={"id": "minecraft:chest"}, facing="south", type="single", waterlogged=False)
    t.put(10, G + 1, 14, "spruce_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    t.put(9, G + 3, 19, "cobweb")
    # The way down to the cellar: a trapdoor and a ladder in the library's corner.
    t.put(11, G, 20, "spruce_trapdoor", facing="north", half="top", open=True, powered=False, waterlogged=False)
    for y in range(1, G):
        t.put(11, y, 20, "ladder", facing="west", waterlogged=False)
    t.put(12, 1, 20, "stone_bricks")

    # The study: a desk, a lantern and the second hiding place.
    sx, sy, sz = SPOTS["page_study"]
    t.put(sx, sy, sz, "chest", nbt={"id": "minecraft:chest"}, facing="south", type="single", waterlogged=False)
    for x in (22, 23, 24):
        t.put(x, G + 1, 13, "spruce_slab", type="top", waterlogged=False)
    t.put(23, G + 1, 14, "spruce_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    t.put(22, G + 2, 13, "lantern", hanging=False, waterlogged=False)
    t.put(24, G + 2, 13, "flower_pot")
    t.put(25, G + 3, 19, "cobweb")

    # Up to the bedrooms: a ladder in the study's corner (and one in the library's, by the cellar trapdoor).
    for y in range(G + 1, G + 6):
        t.put(25, y, 20, "ladder", facing="west", waterlogged=False)
        t.put(7, y, 20, "ladder", facing="east", waterlogged=False)

    # Upstairs: two bedrooms, a chest of old valuables, and a corner where the roof fell in.
    for (bx, bz) in ((9, 11), (23, 15)):
        t.put(bx, G + 6, bz, "red_bed", facing="north", part="foot", occupied=False)
        t.put(bx, G + 6, bz - 1, "red_bed", facing="north", part="head", occupied=False)
    t.put(11, G + 6, 9, "chest", nbt={"id": "minecraft:chest", "LootTable": "bloodline:chests/manor"}, facing="south", type="single",
          waterlogged=False)
    for (x, z) in ((8, 19), (18, 12), (13, 15)):
        t.put(x, G + 9, z, "cobweb")

    roof(t, x0, x1, z0, z1)
    for y in range(G + 3, G + 18):
        t.put(16, y, z0, "stone_bricks")
    t.put(16, G + 18, z0, "stone_brick_wall")
    # The chandelier's chain hangs from the roof beams.
    t.put(16, G + 17, 15, "dark_oak_planks")

def wall_block(y):
    if y <= G + 4:
        return pick("stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks")
    return pick("dark_oak_planks", "spruce_planks", "dark_oak_planks")


def roof(t, x0, x1, z0, z1):
    # A gable along x: slopes down to the north and south, gables at the east and west ends.
    base = G + 10
    for step in range(8):
        y = base + step
        zn, zs = z0 - 1 + step, z1 + 1 - step
        if zn >= zs:
            break
        for x in range(x0 - 1, x1 + 2):
            collapsed = x >= 22 and step < 5 and zn < 12 and rng.random() < 0.8
            if not collapsed:
                t.put(x, y, zn, "deepslate_tile_stairs", facing="south", half="bottom", shape="straight", waterlogged=False)
            t.put(x, y, zs, "deepslate_tile_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
        # The gable ends.
        for z in range(zn + 1, zs):
            for x in (x0, x1):
                t.put(x, y, z, pick("dark_oak_planks", "spruce_planks"))
    t.put(x0, base + 6, 14, "glass_pane")
    # Fallen slates and vines where the roof gave way.
    for (x, z) in ((23, 10), (24, 9), (22, 11), (25, 10)):
        t.put(x, G + 6, z, pick("deepslate_tiles", "cracked_deepslate_tiles", "cobbled_deepslate"))
    for z in range(z0, z1 + 1, 3):
        if rng.random() < 0.6:
            for y in range(G + 2, G + 9):
                if t.get(x0 - 1, y, z) in ("structure_void", "air"):
                    t.put(x0 - 1, y, z, "vine", east=True, north=False, south=False, west=False, up=False)


def garden(t):
    # The walled garden behind the house, with the family graveyard.
    for x in range(1, 32):
        for z in range(1, 8):
            t.put(x, G, z, pick("grass_block", "grass_block", "coarse_dirt", "moss_block"), snowy=False)
    # The graveyard path.
    for x in range(1, 32):
        t.put(x, G, 7, pick("gravel", "coarse_dirt"))
    for z in range(1, 8):
        t.put(16, G, z, pick("gravel", "coarse_dirt"))
    # The lost family's own graves at the east end: three worn headstones without names.
    for x in (28, 30):
        t.put(x, G + 1, 2, pick("mossy_stone_bricks", "cracked_stone_bricks"))
    t.put(29, G + 1, 5, "mossy_stone_bricks")
    # A dead tree over them.
    for y in range(G + 1, G + 5):
        t.put(31, y, 4, "dark_oak_log", axis="y")
    t.put(30, G + 4, 4, "dark_oak_log", axis="x")
    t.put(31, G + 5, 3, "dark_oak_log", axis="z")
    # The fourth hiding place: a chest half hidden by the dead tree.
    gx, gy, gz = SPOTS["page_garden"]
    t.put(gx, gy, gz, "chest", nbt={"id": "minecraft:chest"}, facing="west", type="single", waterlogged=False)
    # Overgrown corners, away from the grave plots.
    for (x, z) in ((1, 1), (2, 1), (1, 4), (31, 1), (1, 6)):
        t.put(x, G + 1, z, pick("dead_bush", "short_grass", "fern"))


if __name__ == "__main__":
    build()
    print("spots:", SPOTS)
    print("graves:", [(x, z) for z in GRAVE_ROWS for x in GRAVE_XS])
