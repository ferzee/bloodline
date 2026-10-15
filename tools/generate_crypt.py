#!/usr/bin/env python3
"""
Generates the barrow crypt's structure pieces (src/main/resources/data/bloodline/structure/crypt/*.nbt).

Run it after changing a piece:  python3 tools/generate_crypt.py

A crypt is a jigsaw structure:
  - entrance: the fixed core. A mausoleum on the surface, a stairway down to the Hall of the Fallen
    (four sarcophagi with epitaphs and four cold braziers on emblem pedestals) and the sealed tomb of the
    fallen house's founder. The mod's Crypts class knows where these are by their positions in this piece:
    keep the CORE_* positions below and the constants in Crypts.java the same.
  - catacombs: random pieces that branch off the hall: corridors, arrow traps, burial halls with spawners,
    an ossuary, a collapsed passage with a secret room, a chapel and dead ends.

Every connector is a 3-wide, 3-high doorway with a jigsaw block at its bottom middle, pointing out of the piece.
Inside the pieces the floor is at y = 0 and you walk at y = 1.
"""
import os
import random

from structure_writer import Template

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "bloodline", "structure", "crypt")
HALLS = "bloodline:crypt/halls"
rng = random.Random(1347)

CRYPT_LOOT = "bloodline:chests/crypt"
TOMB_LOOT = "bloodline:chests/crypt_tomb"

# --- Core positions the mod relies on (template coordinates of the entrance piece) ------------------------
SURF = 16                      # first air layer above the ground; the ground itself is y = 15
FRONT = 7                      # the graveyard in front of the mausoleum; everything else is FRONT blocks further south
# The CORE_* positions are in the mausoleum's own coordinates. In the finished piece (and in Crypts.java) their z is FRONT more.
CORE_BRAZIERS = {"crown": (7, 2, 24), "sword": (13, 2, 24), "harvest": (7, 2, 27), "bone": (13, 2, 27)}
EMBLEM_BLOCKS = {"crown": "gold_block", "sword": "iron_block", "harvest": "hay_block", "bone": "bone_block"}
CORE_SARCOPHAGI = [(2, 21), (2, 29), (17, 21), (17, 29)]   # west end (x, z) of each; each is 2 long in x
CORE_SEAL = (9, 1, 31, 11, 4, 31)
CORE_TOMB = (3, 0, 31, 17, 8, 43)
CORE_BOSS = (10, 1, 35)
CORE_RELIQUARY = (4, 1, 19)


def crumble(t, x, y, z, block="deepslate_bricks"):
    """Old stonework: mostly the given block, sometimes cracked or cobbled."""
    r = rng.random()
    if block == "deepslate_bricks":
        block = "cracked_deepslate_bricks" if r < 0.15 else "cobbled_deepslate" if r < 0.2 else block
    elif block == "deepslate_tiles":
        block = "cracked_deepslate_tiles" if r < 0.15 else block
    elif block == "stone_bricks":
        block = "mossy_stone_bricks" if r < 0.25 else "cracked_stone_bricks" if r < 0.35 else block
    t.put(x, y, z, block)


def shell(t, x1, y1, z1, x2, y2, z2, floor="deepslate_tiles", wall="deepslate_bricks"):
    """A solid box of old stone, its floor (y1) of tiles."""
    for x in range(x1, x2 + 1):
        for y in range(y1, y2 + 1):
            for z in range(z1, z2 + 1):
                crumble(t, x, y, z, floor if y == y1 else wall)


def niche_decor(t, x, y, z, facing):
    """Something laid to rest in a wall niche: a skull, bones, a candle or an urn."""
    r = rng.random()
    if r < 0.3:
        t.put(x, y, z, "skeleton_skull", rotation={"north": 8, "south": 0, "east": 4, "west": 12}[facing], powered=False)
    elif r < 0.5:
        t.put(x, y, z, "bone_block", axis="x" if facing in ("east", "west") else "z")
    elif r < 0.7:
        t.put(x, y, z, "candle", candles=rng.randint(1, 3), lit=rng.random() < 0.5, waterlogged=False)
    elif r < 0.8:
        t.put(x, y, z, "decorated_pot", facing=facing, cracked=False, waterlogged=False)
    elif r < 0.9:
        t.put(x, y, z, "cobweb")
    else:
        t.put(x, y, z, "air")


def door(t, x, z, facing, pool=HALLS, y=1, width_axis="x"):
    """A 3-wide, 3-high doorway centred on (x, z) in the piece's edge, with its connector."""
    for d in (-1, 0, 1):
        for dy in range(3):
            if width_axis == "x":
                t.put(x + d, y + dy, z, "air")
            else:
                t.put(x, y + dy, z + d, "air")
    t.jigsaw(x, y, z, facing, pool)


def soul_lantern(t, x, y, z, chain=1):
    """A soul lantern hanging from the ceiling block above y + chain."""
    t.put(x, y, z, "soul_lantern", hanging=True, waterlogged=False)
    for c in range(1, chain + 1):
        t.put(x, y + c, z, "iron_chain", axis="y", waterlogged=False)


# =====================================================================================================
# The core: mausoleum, stairway, Hall of the Fallen and the sealed tomb
# =====================================================================================================
def grave(t, x, z):
    """A headstone at (x, z) with its grave mound to the south of it. Some graves stand open."""
    G = SURF - 1
    kind = rng.random()
    if kind < 0.12:
        # dug up: an open grave with a skull at the bottom
        crumble(t, x, SURF, z, "stone_bricks")
        t.put(x, SURF, z, "cracked_stone_bricks")
        for dz in (1, 2):
            t.put(x, G - 1, z + dz, "coarse_dirt")
            t.put(x, G, z + dz, "air")
        t.put(x, G, z + 1, "skeleton_skull", rotation=rng.choice([0, 4, 8, 12]), powered=False)
        t.put(x + 1, G, z + 1, "coarse_dirt"); t.put(x + 1, SURF, z + 1, "rooted_dirt")
        return
    if kind < 0.35:
        t.put(x, SURF, z, "mossy_stone_brick_wall")
    elif kind < 0.6:
        t.put(x, SURF, z, "chiseled_stone_bricks")
        t.slab(x, SURF + 1, z, "stone_brick_slab")
        if rng.random() < 0.5:
            t.put(x, SURF + 2, z, "candle", candles=rng.randint(1, 2), lit=rng.random() < 0.6, waterlogged=False)
    elif kind < 0.8:
        t.put(x, SURF, z, "andesite_wall"); t.put(x, SURF + 1, z, "andesite_wall")
    else:
        t.stair(x, SURF, z, rng.choice(["stone_brick_stairs", "mossy_stone_brick_stairs"]), "south")  # a sunken, tilted stone
    for dz in (1, 2):
        mound = rng.choice(["coarse_dirt", "podzol", "rooted_dirt"])
        t.put(x, G, z + dz, mound, **({"snowy": False} if mound == "podzol" else {}))
        t.put(x, SURF, z + dz, "air")
    if rng.random() < 0.45:
        t.put(x, SURF, z + rng.choice([1, 2]), rng.choice(["lily_of_the_valley", "poppy", "allium", "azure_bluet"]))


def obelisk(t, x, z):
    crumble(t, x, SURF, z, "stone_bricks")
    for y in range(SURF + 1, SURF + 4):
        t.put(x, y, z, "polished_deepslate")
    t.put(x, SURF + 4, z, "chiseled_deepslate")
    t.put(x, SURF + 5, z, "deepslate_brick_wall")
    t.put(x, SURF + 6, z, "soul_lantern", hanging=False, waterlogged=False)
    for dx, dz, facing in ((1, 0, "west"), (-1, 0, "east"), (0, 1, "north"), (0, -1, "south")):
        t.stair(x + dx, SURF, z + dz, "stone_brick_stairs", facing)


def dead_tree(t, x, z, tall=6):
    """A bare, crooked tree with hanging moss and a soul lantern on a chain."""
    for y in range(SURF, SURF + tall):
        t.put(x, y, z, "dark_oak_log", axis="y")
    top = SURF + tall
    branches = [((1, 0), "x"), ((-1, 0), "x"), ((0, 1), "z"), ((0, -1), "z")]
    rng.shuffle(branches)
    for i, ((dx, dz), axis) in enumerate(branches[:3]):
        y = top - 1 - i
        length = 2 + (i == 0)
        for step in range(1, length + 1):
            t.put(x + dx * step, y + (step == length), z + dz * step, "dark_oak_log", axis=axis if step < length else "y")
        if i == 0:
            t.put(x + dx * (length - 1), y - 1, z + dz * (length - 1), "iron_chain", axis="y", waterlogged=False)
            t.put(x + dx * (length - 1), y - 2, z + dz * (length - 1), "soul_lantern", hanging=True, waterlogged=False)
        else:
            t.put(x + dx * step, y - 1, z + dz * step, "pale_hanging_moss", tip=True)
    t.put(x, top, z, "dark_oak_log", axis="y")


def graveyard(t):
    """A walled churchyard around the mausoleum (x 0..20, z 0..30), cut level into the ground."""
    G = SURF - 1
    X1, Z1 = 20, 30
    for x in range(0, X1 + 1):
        for z in range(0, Z1 + 1):
            for y in range(G - 4, G):
                t.put(x, y, z, "dirt")  # so the yard doesn't float over a small dip
            r = rng.random()
            t.put(x, G, z, "podzol" if r < 0.15 else "coarse_dirt" if r < 0.25 else "moss_block" if r < 0.32 else "grass_block",
                  **({"snowy": False} if r < 0.15 or r >= 0.32 else {}))
            for y in range(SURF, SURF + 8):
                t.put(x, y, z, "air")
            c = rng.random()
            ground = t.get(x, G, z)
            if c < 0.14 and ground in ("grass_block", "podzol"):
                t.put(x, SURF, z, "short_grass")
            elif c < 0.19 and ground in ("grass_block", "podzol"):
                t.put(x, SURF, z, "fern")
            elif c < 0.24 and ground == "moss_block":
                t.put(x, SURF, z, "moss_carpet")
            elif c < 0.26:
                t.put(x, SURF, z, "dead_bush")
    # the wall, with pillars every few blocks and iron railings along the front
    for x in range(0, X1 + 1):
        for z in range(0, Z1 + 1):
            if x in (0, X1) or z in (0, Z1):
                crumble(t, x, G, z, "stone_bricks")
                t.put(x, SURF, z, rng.choice(["stone_brick_wall", "mossy_stone_brick_wall", "mossy_stone_brick_wall", "cobblestone_wall"]))
                if z == 0:
                    t.put(x, SURF + 1, z, "iron_bars")
    for x in (0, 5, 15, 20):
        for z in (0, 10, 20, 30):
            if x in (0, 20) or z in (0, 30):
                crumble(t, x, SURF, z, "stone_bricks"); crumble(t, x, SURF + 1, z, "stone_bricks")
                t.put(x, SURF + 2, z, "chiseled_stone_bricks")
                if (x, z) in ((0, 0), (20, 0), (0, 30), (20, 30)):
                    t.put(x, SURF + 3, z, "soul_lantern", hanging=False, waterlogged=False)
    # the gate: two pillars topped with skulls under an arch with a hanging lantern
    t.air(9, SURF, 0, 11, SURF + 1, 0)
    for x in (8, 12):
        for y in range(SURF, SURF + 3):
            crumble(t, x, y, 0, "stone_bricks")
        t.put(x, SURF + 3, 0, "chiseled_stone_bricks")
        t.put(x, SURF + 4, 0, "skeleton_skull", rotation=8, powered=False)
    t.stair(9, SURF + 3, 0, "stone_brick_stairs", "east", top=True)
    t.stair(11, SURF + 3, 0, "stone_brick_stairs", "west", top=True)
    t.put(10, SURF + 3, 0, "chiseled_stone_bricks")
    t.slab(9, SURF + 4, 0, "stone_brick_slab"); t.slab(11, SURF + 4, 0, "stone_brick_slab")
    t.put(10, SURF + 4, 0, "stone_bricks")
    t.put(10, SURF + 2, 0, "soul_lantern", hanging=True, waterlogged=False)
    # the path from the gate to the mausoleum
    for z in range(0, FRONT + 1):
        for x in range(9, 12):
            t.put(x, G, z, rng.choice(["gravel", "gravel", "coarse_dirt", "cobblestone", "mossy_cobblestone"]))
            t.put(x, SURF, z, "air")
    # rows of graves in front, beside and behind the mausoleum
    for x in (2, 4, 6, 14, 16, 18):
        grave(t, x, 2)
    for x in (2, 6, 14, 18):
        grave(t, x, 5)
    for z in (14, 22):
        grave(t, 2, z)
        grave(t, 18, z + 2)
    for x in (2, 5, 15, 18):
        grave(t, x, 21)
        grave(t, x, 25)
    # a chest tomb in the back, with candles on its lid
    for x in range(9, 12):
        for z in range(22, 25):
            crumble(t, x, SURF, z, "stone_bricks")
            t.slab(x, SURF + 1, z, "polished_deepslate_slab")
    t.put(9, SURF + 2, 22, "candle", candles=3, lit=True, waterlogged=False)
    t.put(11, SURF + 2, 24, "candle", candles=2, lit=False, waterlogged=False)
    t.put(10, SURF + 2, 23, "skeleton_skull", rotation=0, powered=False)
    # obelisks beside the mausoleum and dead trees in the corners
    obelisk(t, 2, 10)
    obelisk(t, 18, 10)
    dead_tree(t, 3, 27, tall=6)
    dead_tree(t, 17, 26, tall=5)
    dead_tree(t, 3, 19, tall=4)


def entrance():
    t = Template((21, SURF + 19, 44 + FRONT))
    G = SURF - 1  # the ground layer
    graveyard(t)
    # Everything below was laid out before the graveyard was added in front of it.
    t.offset = (0, 0, FRONT)

    # --- Mausoleum on the surface (walls x 5..15, z 1..11) ------------------------------------------------
    for x in range(5, 16):
        for z in range(1, 12):
            crumble(t, x, G, z, "stone_bricks")
    for x in range(7, 14):
        t.stair(x, G, 0, "stone_brick_stairs", "south")
    for y in range(SURF, SURF + 5):
        for x in range(5, 16):
            for z in range(1, 12):
                if x in (5, 15) or z in (1, 11):
                    crumble(t, x, y, z, "stone_bricks")
                else:
                    t.put(x, y, z, "air")
        for x, z in [(5, 1), (15, 1), (5, 11), (15, 11), (5, 6), (15, 6)]:
            t.put(x, y, z, "polished_deepslate")
    # buttresses along the sides and at the front corners, with gargoyle skulls on the front ones
    for z in (1, 6, 11):
        for x, facing in ((4, "east"), (16, "west")):
            crumble(t, x, SURF, z, "stone_bricks")
            crumble(t, x, SURF + 1, z, "stone_bricks")
            t.stair(x, SURF + 2, z, "stone_brick_stairs", facing)
    for x in (5, 15):
        crumble(t, x, SURF, 0, "stone_bricks")
        t.stair(x, SURF + 1, 0, "stone_brick_stairs", "south")
        t.put(x, SURF + 3, 0, "wither_skeleton_wall_skull", facing="north", powered=False)
    # the doorway, with a rusted portcullis half raised
    t.air(9, SURF, 1, 11, SURF + 2, 1)
    for x in (9, 11):
        t.stair(x, SURF + 3, 1, "stone_brick_stairs", "west" if x == 9 else "east", top=True)
    t.put(10, SURF + 3, 1, "chiseled_stone_bricks")
    for x in range(9, 12):
        t.put(x, SURF + 2, 1, "iron_bars")
    t.put(9, SURF + 1, 1, "cobweb")
    for x in (7, 13):
        t.put(x, SURF, 0, "cobbled_deepslate_wall")
        t.put(x, SURF + 1, 0, "soul_lantern", hanging=False, waterlogged=False)
    # tall stained-glass windows
    for z in (3, 4, 8, 9):
        for x in (5, 15):
            for y in (SURF + 1, SURF + 2, SURF + 3):
                t.put(x, y, z, "purple_stained_glass_pane" if y < SURF + 3 else "black_stained_glass_pane")
    # a steep slate roof, ridge running front to back, open to the rafters inside
    for k in range(0, 6):
        y = SURF + 4 + 2 * k
        for z in range(-1, 13):
            t.put(4 + k, y, z, "deepslate_tiles"); t.stair(4 + k, y + 1, z, "deepslate_tile_stairs", "east")
            t.put(16 - k, y, z, "deepslate_tiles"); t.stair(16 - k, y + 1, z, "deepslate_tile_stairs", "west")
            for x in range(5 + k, 16 - k):
                for yy in (y, y + 1):
                    if z in (1, 11) or (k == 0 and x in (5, 15) and 1 <= z <= 11):
                        crumble(t, x, yy, z, "stone_bricks")  # gable ends, and the side walls up to the roof
                    elif 1 < z < 11:
                        t.put(x, yy, z, "air")
    for z in range(-1, 13):
        t.put(10, SURF + 15, z, "deepslate_tiles")
        t.slab(10, SURF + 16, z, "deepslate_tile_slab")
    # a rose window in the front gable
    for x, y in [(10, SURF + 6), (9, SURF + 7), (11, SURF + 7), (10, SURF + 8), (10, SURF + 7)]:
        t.put(x, y, 1, "black_stained_glass_pane" if (x, y) == (10, SURF + 7) else "purple_stained_glass_pane")
    # finials with soul lanterns on both ends of the ridge
    for z in (-1, 12):
        t.put(10, SURF + 17, z, "deepslate_brick_wall")
        t.put(10, SURF + 18, z, "soul_lantern", hanging=False, waterlogged=False)
    # inside: candles, an urn and a skull by the stairs, a soul lantern on a long chain
    for x, z in [(6, 2), (14, 2), (6, 10), (14, 10)]:
        t.put(x, SURF, z, "candle", candles=3, lit=True, waterlogged=False)
    t.put(7, SURF, 10, "decorated_pot", facing="south", cracked=True, waterlogged=False)
    t.put(13, SURF, 10, "skeleton_skull", rotation=0, powered=False)
    soul_lantern(t, 10, SURF + 5, 6, chain=9)
    for x, z in [(6, 2), (14, 10)]:
        t.put(x, SURF + 4, z, "cobweb")
    # railings around the stairwell
    for z in range(4, 11):
        t.put(8, SURF, z, "deepslate_brick_wall"); t.put(12, SURF, z, "deepslate_brick_wall")
    for x in (8, 9, 11, 12):
        t.put(x, SURF, 11, "deepslate_brick_wall")

    # --- The stairway down (x 9..11), one step per block south --------------------------------------------
    for i in range(0, SURF - 2):
        z, y = 4 + i, SURF - 2 - i
        if z <= 10:
            # under the mausoleum: walls up to its floor, open above the steps
            for yy in range(y - 1, G):
                crumble(t, 8, yy, z); crumble(t, 12, yy, z)
            t.air(9, y + 1, z, 11, G, z)
        else:
            # underground: a vaulted tunnel of old bricks
            for yy in range(y - 1, y + 6):
                for x in range(8, 13):
                    crumble(t, x, yy, z)
            t.air(9, y + 1, z, 11, y + 4, z)
            if i % 4 == 2:
                soul_lantern(t, 10, y + 4, z, chain=0)
        for x in range(9, 12):
            crumble(t, x, y - 1, z)
            t.stair(x, y, z, "deepslate_brick_stairs", "north")
    # the top step sits in the mausoleum floor, so climbing out needs no jump
    for x in range(9, 12):
        t.stair(x, G, 3, "deepslate_brick_stairs", "north")

    # --- Hall of the Fallen (walls x 1..19, z 18..31, floor y 0, ceiling y 7) --------------------------------
    shell(t, 1, 0, 18, 19, 7, 31)
    t.air(2, 1, 19, 18, 6, 30)
    t.air(9, 1, 18, 11, 4, 18)  # where the stairway comes in
    # vaulted ceiling
    for x in range(2, 19):
        t.stair(x, 6, 19, "deepslate_brick_stairs", "north", top=True)
        t.stair(x, 6, 30, "deepslate_brick_stairs", "south", top=True)
    for z in range(19, 31):
        t.stair(2, 6, z, "deepslate_brick_stairs", "west", top=True)
        t.stair(18, 6, z, "deepslate_brick_stairs", "east", top=True)
    # floor: a path of polished deepslate from the stairs to the seal
    for z in range(19, 31):
        for x in range(9, 12):
            t.put(x, 0, z, "polished_deepslate")
    for x in range(6, 15):
        for z in range(23, 29):
            if 9 <= x <= 11:
                continue
            t.put(x, 0, z, "deepslate_tiles")
    # the four sarcophagi of the founder's children, with plaques above them
    for x0, z in CORE_SARCOPHAGI:
        head = x0 if x0 < 10 else x0 + 1
        foot = x0 + 1 if x0 < 10 else x0
        for x in (head, foot):
            t.put(x, 1, z, "polished_deepslate")
        t.slab(foot, 2, z, "polished_deepslate_slab")
        t.put(head, 2, z, "skeleton_skull", rotation=4 if x0 < 10 else 12, powered=False)
        wall = 1 if x0 < 10 else 19
        t.put(wall, 3, z, "chiseled_deepslate")
        t.put(foot + (1 if x0 < 10 else -1), 1, z, "candle", candles=2, lit=True, waterlogged=False)
    # the four braziers on their emblem pedestals
    for emblem, (x, y, z) in CORE_BRAZIERS.items():
        t.put(x, y - 1, z, EMBLEM_BLOCKS[emblem], **({"axis": "y"} if emblem in ("harvest", "bone") else {}))
        t.put(x, y, z, "soul_campfire", facing="south", lit=False, signal_fire=False, waterlogged=False)
    # hanging soul lanterns
    for x, z in [(6, 21), (14, 21), (6, 29), (14, 29)]:
        soul_lantern(t, x, 4, z, chain=2)
    # the reliquary: a chest in an alcove by the stairs
    rx, ry, rz = CORE_RELIQUARY
    t.chest(rx, ry, rz, "south", CRYPT_LOOT)
    t.put(rx - 1, 1, rz, "candle", candles=1, lit=False, waterlogged=False)
    t.put(16, 1, 19, "decorated_pot", facing="south", cracked=True, waterlogged=False)
    # cobwebs in the upper corners
    for x, z in [(2, 19), (18, 19), (2, 30), (18, 30)]:
        t.put(x, 5, z, "cobweb")
    # doorways into the catacombs, west and east
    door(t, 1, 25, "west", width_axis="z")
    door(t, 19, 25, "east", width_axis="z")

    # --- The seal and the tomb (x 3..17, z 31..43) ----------------------------------------------------------
    shell(t, 3, 0, 31, 17, 8, 43, floor="polished_blackstone", wall="deepslate_bricks")
    t.air(4, 1, 32, 16, 7, 42)
    sx1, sy1, sz, sx2, sy2, _ = CORE_SEAL
    for x in range(sx1 - 1, sx2 + 2):
        for y in range(sy1, sy2 + 2):
            t.put(x, y, sz, "polished_blackstone_bricks")
    for x in range(sx1, sx2 + 1):
        for y in range(sy1, sy2 + 1):
            t.put(x, y, sz, "polished_blackstone_bricks")
    t.put(10, 2, sz, "chiseled_polished_blackstone")
    t.put(10, 3, sz, "gilded_blackstone")
    t.put(9, 4, sz, "gilded_blackstone"); t.put(11, 4, sz, "gilded_blackstone")
    t.put(10, 6, sz - 1, "wither_skeleton_wall_skull", facing="north", powered=False)
    t.put(8, 1, sz - 1, "soul_campfire", facing="north", lit=True, signal_fire=False, waterlogged=False)
    t.put(12, 1, sz - 1, "soul_campfire", facing="north", lit=True, signal_fire=False, waterlogged=False)
    # inside: pillars, soul fires, the founder's sarcophagus on a dais, banners and the tomb's hoard
    for x, z in [(6, 35), (14, 35), (6, 39), (14, 39)]:
        for y in range(1, 8):
            t.put(x, y, z, "polished_blackstone_bricks")
    for x, z in [(5, 33), (15, 33), (5, 41), (15, 41)]:
        t.put(x, 1, z, "soul_campfire", facing="north", lit=True, signal_fire=False, waterlogged=False)
    for x in range(8, 13):
        for z in range(37, 42):
            t.put(x, 0, z, "gilded_blackstone" if (x + z) % 3 == 0 else "polished_blackstone")
    for x in range(9, 12):
        for z in range(38, 41):
            t.put(x, 1, z, "polished_blackstone")
        t.slab(x, 2, 38, "polished_blackstone_slab")
        t.slab(x, 2, 39, "polished_blackstone_slab")
        t.slab(x, 2, 40, "polished_blackstone_slab")
    t.put(10, 2, 40, "wither_skeleton_skull", rotation=0, powered=False)
    for x in (8, 12):
        t.put(x, 4, 42, "black_wall_banner", facing="north")
    t.chest(10, 1, 42, "north", TOMB_LOOT)
    soul_lantern(t, 10, 5, 37, chain=2)
    t.connect()
    return t


# =====================================================================================================
# Catacombs
# =====================================================================================================
def corridor(trap=False):
    t = Template((7, 6, 9))
    shell(t, 0, 0, 0, 6, 5, 8)
    t.air(2, 1, 0, 4, 4, 8)
    for z in (0, 8):
        for x in range(2, 5):
            crumble(t, x, 4, z)  # lintels
    door(t, 3, 0, "north", pool="minecraft:empty")
    door(t, 3, 8, "south")
    for z in (2, 4, 6):
        for x, facing in ((1, "east"), (5, "west")):
            if trap and z == 4:
                continue
            t.put(x, 1, z, "air"); t.put(x, 2, z, "air")
            niche_decor(t, x, 1, z, facing)
    if trap:
        # the passage narrows between two pillars; a pressure plate between them sets off the dispensers
        for z in (3, 5):
            for x in (2, 4):
                t.put(x, 1, z, "chiseled_deepslate"); t.put(x, 2, z, "chiseled_deepslate")
        arrows = [{"Slot": 0, "id": "minecraft:arrow", "count": 9}]
        t.put(2, 1, 4, "dispenser", nbt={"id": "minecraft:dispenser", "Items": arrows}, facing="east", triggered=False)
        t.put(4, 1, 4, "dispenser", nbt={"id": "minecraft:dispenser", "Items": arrows}, facing="west", triggered=False)
        t.put(2, 2, 4, "chiseled_deepslate"); t.put(4, 2, 4, "chiseled_deepslate")
        t.put(3, 0, 4, "polished_blackstone")
        t.put(3, 1, 4, "polished_blackstone_pressure_plate", powered=False)
        t.put(3, 3, 4, "cobweb")
    else:
        soul_lantern(t, 3, 4, 4, chain=0)
    return t


def corner(side):
    t = Template((7, 6, 7))
    shell(t, 0, 0, 0, 6, 5, 6)
    t.air(2, 1, 0, 4, 4, 4)
    if side == "left":
        t.air(0, 1, 2, 4, 4, 4)
        door(t, 0, 3, "west", width_axis="z")
    else:
        t.air(2, 1, 2, 6, 4, 4)
        door(t, 6, 3, "east", width_axis="z")
    door(t, 3, 0, "north", pool="minecraft:empty")
    for x in range(2, 5):
        crumble(t, x, 4, 0)
    t.put(3 if side == "left" else 3, 1, 5, "air"); t.put(3, 2, 5, "air")
    niche_decor(t, 3, 1, 5, "north")
    t.put(2 if side == "right" else 4, 4, 4, "cobweb")
    return t


def crossroads():
    t = Template((11, 7, 11))
    shell(t, 0, 0, 0, 10, 6, 10)
    for x in range(1, 10):
        for z in range(1, 10):
            if (x - 5) ** 2 + (z - 5) ** 2 <= 17:
                t.air(x, 1, z, x, 5, z)
    door(t, 5, 0, "north", pool="minecraft:empty")
    door(t, 0, 5, "west", width_axis="z")
    door(t, 10, 5, "east", width_axis="z")
    door(t, 5, 10, "south")
    # an ossuary pillar of bones and skulls in the middle, a soul lantern above it
    for y in range(1, 6):
        t.put(5, y, 5, "bone_block", axis="y")
    for (x, z), rot in [((5, 4), 8), ((5, 6), 0), ((4, 5), 12), ((6, 5), 4)]:
        t.put(x, 1, z, "skeleton_skull", rotation=rot, powered=False)
    for x, z in [(3, 3), (7, 3), (3, 7), (7, 7)]:
        t.put(x, 1, z, "candle", candles=rng.randint(1, 4), lit=rng.random() < 0.6, waterlogged=False)
    soul_lantern(t, 5, 4, 3, chain=1)
    return t


def burial_hall():
    t = Template((13, 8, 15))
    shell(t, 0, 0, 0, 12, 7, 14)
    t.air(1, 1, 1, 11, 6, 13)
    door(t, 6, 0, "north", pool="minecraft:empty")
    door(t, 6, 14, "south")
    door(t, 12, 7, "east", width_axis="z")
    # rows of sarcophagi
    for x0, zs in ((2, (3, 6, 9, 12)), (9, (3, 11))):
        for z in zs:
            t.put(x0, 1, z, "polished_deepslate"); t.put(x0 + 1, 1, z, "polished_deepslate")
            t.slab(x0, 2, z, "polished_deepslate_slab")
            if rng.random() < 0.6:
                t.put(x0 + 1, 2, z, "skeleton_skull", rotation=rng.choice([0, 4, 8, 12]), powered=False)
            else:
                t.slab(x0 + 1, 2, z, "polished_deepslate_slab")
    # the spawner on a dais, among candles
    for x in range(5, 8):
        for z in range(6, 9):
            t.put(x, 0, z, "polished_blackstone")
    t.put(6, 1, 7, "spawner", nbt={"id": "minecraft:mob_spawner", "SpawnData": {"entity": {"id": "minecraft:skeleton"}}})
    for x, z in [(5, 6), (7, 6), (5, 8), (7, 8)]:
        t.put(x, 1, z, "candle", candles=rng.randint(1, 3), lit=True, waterlogged=False)
    t.chest(11, 1, 13, "west", CRYPT_LOOT)
    for x, z in [(3, 7), (10, 7)]:
        soul_lantern(t, x, 5, z, chain=1)
    for x, z in [(1, 1), (11, 1), (1, 13)]:
        t.put(x, 6, z, "cobweb")
    return t


def ossuary():
    t = Template((11, 7, 11))
    shell(t, 0, 0, 0, 10, 6, 10)
    t.air(1, 1, 1, 9, 5, 9)
    door(t, 5, 0, "north", pool="minecraft:empty")
    door(t, 10, 5, "east", width_axis="z")
    # walls lined with bones and skulls
    for y in (1, 2, 3, 4):
        for z in range(1, 10):
            if t.get(1, y, z) == "air" and z != 5:
                t.put(1, y, z, "bone_block", axis="x") if (y + z) % 3 else t.put(1, y, z, "skeleton_skull", rotation=4, powered=False)
        for x in range(2, 9):
            if t.get(x, y, 9) == "air":
                t.put(x, y, 9, "bone_block", axis="z") if (y + x) % 3 else t.put(x, y, 9, "skeleton_skull", rotation=8, powered=False)
    # a shallow pool of black water in the middle, ringed by slabs
    for x in range(3, 8):
        for z in range(3, 8):
            edge = x in (3, 7) or z in (3, 7)
            if edge:
                t.slab(x, 1, z, "polished_deepslate_slab")
            else:
                t.put(x, 1, z, "water", level=0)
    # old graves to brush: suspicious gravel in the floor
    for x, z in [(2, 2), (8, 2), (2, 8), (8, 8), (5, 1)]:
        t.put(x, 0, z, "suspicious_gravel", nbt={"id": "minecraft:brushable_block", "LootTable": "bloodline:archaeology/crypt"}, dusted=0)
    soul_lantern(t, 5, 4, 5, chain=1)
    t.put(8, 5, 1, "cobweb")
    return t


def collapsed():
    t = Template((11, 6, 11))
    shell(t, 0, 0, 0, 10, 5, 10)
    t.air(2, 1, 0, 4, 4, 10)
    for z in (0, 10):
        for x in range(2, 5):
            crumble(t, x, 4, z)
    door(t, 3, 0, "north", pool="minecraft:empty")
    door(t, 3, 10, "south")
    # a cave-in: rubble to dig through
    for z in range(4, 8):
        for x in range(2, 5):
            for y in range(1, 5):
                if y <= 1 + (2 - abs(z - 5.5)) * 1.5 or rng.random() < 0.35:
                    t.put(x, y, z, rng.choice(["gravel", "cobbled_deepslate", "tuff", "gravel"]))
    # the secret: one wall block is chiseled, with a dead candle in front of it. Behind it is a sealed room.
    t.put(5, 1, 2, "chiseled_deepslate"); t.put(5, 2, 2, "chiseled_deepslate")
    t.put(4, 1, 2, "candle", candles=1, lit=False, waterlogged=False)
    t.air(6, 1, 1, 9, 3, 3)
    t.chest(9, 1, 2, "west", CRYPT_LOOT)
    t.put(8, 1, 1, "skeleton_skull", rotation=0, powered=False)
    t.put(8, 1, 3, "decorated_pot", facing="north", cracked=False, waterlogged=False)
    t.put(6, 3, 1, "cobweb")
    t.put(7, 1, 3, "candle", candles=3, lit=False, waterlogged=False)
    return t


def chapel():
    t = Template((9, 8, 9))
    shell(t, 0, 0, 0, 8, 7, 8)
    t.air(1, 1, 1, 7, 6, 7)
    door(t, 4, 0, "north", pool="minecraft:empty")
    # pews and an altar
    for z in (3, 5):
        for x in (2, 3, 5, 6):
            t.stair(x, 1, z, "dark_oak_stairs", "south")
    for x in range(3, 6):
        t.put(x, 1, 7, "polished_blackstone_bricks")
    t.put(4, 2, 7, "candle", candles=4, lit=True, waterlogged=False)
    t.put(3, 2, 7, "skeleton_skull", rotation=8, powered=False)
    t.put(5, 2, 7, "decorated_pot", facing="north", cracked=True, waterlogged=False)
    t.put(1, 1, 7, "lectern", facing="east", has_book=False, powered=False)
    t.chest(7, 1, 7, "west", CRYPT_LOOT)
    for x in (1, 7):
        t.put(x, 3, 4, "black_wall_banner", facing="east" if x == 1 else "west")
    soul_lantern(t, 4, 5, 4, chain=1)
    return t


def cap_niche():
    t = Template((5, 5, 3))
    shell(t, 0, 0, 0, 4, 4, 2)
    t.air(1, 1, 0, 3, 3, 1)
    t.jigsaw(2, 1, 0, "north", "minecraft:empty")
    t.put(2, 1, 1, "decorated_pot", facing="north", cracked=rng.random() < 0.5, waterlogged=False)
    t.put(1, 1, 1, "candle", candles=2, lit=False, waterlogged=False)
    t.put(3, 1, 1, "skeleton_skull", rotation=8, powered=False)
    t.put(3, 3, 1, "cobweb")
    return t


def cap_wall():
    """A doorway bricked up long ago."""
    t = Template((5, 5, 1))
    shell(t, 0, 0, 0, 4, 4, 0)
    for x in range(1, 4):
        for y in range(1, 4):
            t.put(x, y, 0, rng.choice(["cobbled_deepslate", "cracked_deepslate_bricks", "tuff"]))
    t.jigsaw(2, 1, 0, "north", "minecraft:empty", final_state="minecraft:cobbled_deepslate")
    return t


PIECES = {
    "entrance": entrance,
    "corridor": corridor,
    "corridor_trap": lambda: corridor(trap=True),
    "corner_left": lambda: corner("left"),
    "corner_right": lambda: corner("right"),
    "crossroads": crossroads,
    "burial_hall": burial_hall,
    "ossuary": ossuary,
    "collapsed": collapsed,
    "chapel": chapel,
    "cap_niche": cap_niche,
    "cap_wall": cap_wall,
}

if __name__ == "__main__":
    for name, build in PIECES.items():
        template = build()
        template.connect()
        count = template.write(os.path.join(OUT, name + ".nbt"))
        print(f"{name:14} {count:5} blocks  size {template.size}")
