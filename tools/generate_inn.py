#!/usr/bin/env python3
"""
Generates the roadside inn's structure file (src/main/resources/data/bloodline/structure/roadside_inn.nbt).

Run it after changing the building:  python3 tools/generate_inn.py
A builder can also replace the .nbt with one saved in-game with a structure block; the mod
doesn't care how the file was made, as long as it keeps a bell inside (that's where the innkeeper appears,
on the side the bell doesn't face).

The inn:
  - a timber-framed hall on a stone plinth, its upper storey jutting out over the street (a "jetty")
  - a slate roof with dormer windows, roof trusses and a chimney that smokes
  - inside: an inglenook fireplace with armchairs, long tables, a bar with kegs, a kitchen corner
  - upstairs: a landing, five guest rooms and the innkeeper's own room with a strongbox
  - a lantern tower whose signal fire smokes high above the trees, so travellers can find the inn
  - a stable with a horse and a donkey, a well, a signpost, lamp posts and flower beds out front
  - a kitchen garden with crops and a scarecrow behind, and an inn cat by the fire

Coordinates: x 0..30 (west to east), y 0..24 (up), z 0..28 (north to south). The road side is north (z = 0).
y = 0 is ground level: the floor of the buildings and the paths outside.
"""
import gzip
import os
import random
import struct

DATA_VERSION = 5023  # Minecraft 26.3
SIZE = (31, 25, 29)
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "bloodline", "structure", "roadside_inn.nbt")

rng = random.Random(1940)
blocks = {}       # (x, y, z) -> (name, props)
block_nbt = {}    # (x, y, z) -> SNBT-ish python dict for block entities
entities = []     # (pos, nbt dict)


def put(x, y, z, block, **props):
    blocks[(x, y, z)] = (block, {k: str(v).lower() for k, v in props.items()})
    block_nbt.pop((x, y, z), None)


def fill(x1, y1, z1, x2, y2, z2, block, **props):
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                put(x, y, z, block, **props)


def air(x1, y1, z1, x2, y2, z2):
    fill(x1, y1, z1, x2, y2, z2, "air")


def stone(x, y, z):
    """Weathered stonework: mostly cobblestone, some mossy, some stone bricks."""
    r = rng.random()
    put(x, y, z, "mossy_cobblestone" if r < 0.25 else "stone_bricks" if r < 0.4 else "cobblestone")


def stair(x, y, z, block, facing, top=False):
    put(x, y, z, block, facing=facing, half="top" if top else "bottom", shape="straight", waterlogged=False)


def slab(x, y, z, block, top=False):
    put(x, y, z, block, type="top" if top else "bottom", waterlogged=False)


def lantern(x, y, z, hanging=False):
    put(x, y, z, "lantern", hanging=hanging, waterlogged=False)


def door(x, z, y, facing, hinge="left", block="spruce_door"):
    put(x, y, z, block, facing=facing, half="lower", hinge=hinge, open=False, powered=False)
    put(x, y + 1, z, block, facing=facing, half="upper", hinge=hinge, open=False, powered=False)


def pane(x, y, z):
    put(x, y, z, "glass_pane")  # connections are filled in at the end


def fence(x, y, z, block="spruce_fence"):
    put(x, y, z, block)  # connections are filled in at the end


def bed(x, y, z, colour, facing):
    """The bed's foot at (x, z), its head one block towards `facing`."""
    dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]
    put(x, y, z, f"{colour}_bed", facing=facing, part="foot", occupied=False)
    put(x + dx, y, z + dz, f"{colour}_bed", facing=facing, part="head", occupied=False)


def chest(x, y, z, facing, loot):
    put(x, y, z, "chest", facing=facing, type="single", waterlogged=False)
    block_nbt[(x, y, z)] = {"id": "minecraft:chest", "LootTable": loot}


def flower(x, y, z):
    put(x, y, z, rng.choice(["poppy", "dandelion", "cornflower", "oxeye_daisy", "allium", "azure_bluet", "red_tulip"]))


def potted(x, y, z):
    put(x, y, z, rng.choice(["potted_red_tulip", "potted_blue_orchid", "potted_fern", "potted_allium", "potted_oxeye_daisy"]))


# =====================================================================================================
# The hall: ground floor walls x 4..20, z 8..18; the upper storey juts out to z 7..19.
# =====================================================================================================
X0, X1 = 4, 20      # gable walls (west, east)
Z0, Z1 = 8, 18      # ground floor walls (front, back)
J0, J1 = 7, 19      # upper storey walls (jettied)
POSTS = (4, 10, 15, 20)   # timber posts line up with the walls between the rooms upstairs

# --- Plinth and floor ---------------------------------------------------------------------------------
for x in range(X0, X1 + 1):
    for z in range(Z0, Z1 + 1):
        if x in (X0, X1) or z in (Z0, Z1):
            stone(x, 0, z)
        else:
            put(x, 0, z, "spruce_planks")

# --- Ground floor walls (y 1..4): a stone lower course, then planks between dark oak posts ----------------
for y in range(1, 5):
    for x in range(X0, X1 + 1):
        for z in (Z0, Z1):
            if y == 1:
                stone(x, y, z)
            else:
                put(x, y, z, "spruce_planks")
    for z in range(Z0, Z1 + 1):
        for x in (X0, X1):
            if y == 1:
                stone(x, y, z)
            else:
                put(x, y, z, "spruce_planks")
    for x in POSTS:
        put(x, y, Z0, "stripped_dark_oak_log", axis="y")
        put(x, y, Z1, "stripped_dark_oak_log", axis="y")
    for z in (Z0, 13, Z1):
        put(X0, y, z, "stripped_dark_oak_log", axis="y")
        put(X1, y, z, "stripped_dark_oak_log", axis="y")
air(X0 + 1, 1, Z0 + 1, X1 - 1, 4, Z1 - 1)

# Ground floor windows
for x in (6, 7, 8, 16, 17, 18):
    pane(x, 2, Z0); pane(x, 3, Z0)
for x in (13, 14, 17, 18):
    pane(x, 2, Z1); pane(x, 3, Z1)
for z in (10, 16):
    pane(X0, 2, z); pane(X0, 3, z)
for z in (10, 11):
    pane(X1, 2, z); pane(X1, 3, z)

# Front double door, back door
door(11, Z0, 1, "south", "right")  # hinges on the outer sides, so the pair opens in the middle
door(12, Z0, 1, "south", "left")
door(12, Z1, 1, "north", "left")

# --- Upper floor (y 5) with the jetty beams and corbels --------------------------------------------------
fill(X0, 5, J0, X1, 5, J1, "spruce_planks")
for x in range(X0, X1 + 1):
    put(x, 5, J0, "dark_oak_log", axis="x")
    put(x, 5, J1, "dark_oak_log", axis="x")
for x in POSTS:
    stair(x, 4, J0, "dark_oak_stairs", "south", top=True)
    stair(x, 4, J1, "dark_oak_stairs", "north", top=True)

# --- Upper walls (y 6..10): white infill in a dark oak frame -------------------------------------------
for y in range(6, 11):
    for x in range(X0, X1 + 1):
        for z in (J0, J1):
            put(x, y, z, "white_terracotta")
    for z in range(J0, J1 + 1):
        for x in (X0, X1):
            put(x, y, z, "white_terracotta")
    for x in POSTS:
        put(x, y, J0, "dark_oak_log", axis="y")
        put(x, y, J1, "dark_oak_log", axis="y")
    for z in (J0, 13, J1):
        put(X0, y, z, "dark_oak_log", axis="y")
        put(X1, y, z, "dark_oak_log", axis="y")
for x in range(X0, X1 + 1):  # a beam under the eaves
    put(x, 9, J0, "dark_oak_log", axis="x")
    put(x, 9, J1, "dark_oak_log", axis="x")
for z in range(J0, J1 + 1):
    put(X0, 9, z, "dark_oak_log", axis="z")
    put(X1, 9, z, "dark_oak_log", axis="z")
air(X0 + 1, 6, J0 + 1, X1 - 1, 10, J1 - 1)

# Upper windows (with flower boxes under the front ones)
for x in (6, 7, 12, 13, 17, 18):
    pane(x, 7, J0); pane(x, 8, J0)
    put(x, 6, J0 - 1, "spruce_trapdoor", facing="north", half="top", open=True, waterlogged=False, powered=False)
for x in (7, 12, 13, 17, 18):
    pane(x, 7, J1); pane(x, 8, J1)
for z in (10, 16):
    pane(X0, 7, z); pane(X0, 8, z)
pane(X1, 7, 10); pane(X1, 8, 10)

# --- Slate roof: ridge along x at z 13, eaves overhanging one block on every side ------------------------
for k in range(0, 7):
    y = 10 + k
    for x in range(X0 - 1, X1 + 2):
        stair(x, y, J0 - 1 + k, "deepslate_tile_stairs", "south")
        stair(x, y, J1 + 1 - k, "deepslate_tile_stairs", "north")
    if k >= 1:
        # gable ends: white infill with a dark oak frame along the roof line
        for z in range(J0 + k, J1 - k + 1):
            for x in (X0, X1):
                edge = z in (J0 + k, J1 - k)
                put(x, y, z, "dark_oak_planks" if edge else "white_terracotta")
            for x in range(X0 + 1, X1):
                put(x, y, z, "air")
for x in range(X0 - 1, X1 + 2):
    put(x, 16, 13, "deepslate_tiles")
    slab(x, 17, 13, "deepslate_tile_slab")
put(X0, 16, 13, "dark_oak_planks"); put(X1, 16, 13, "dark_oak_planks")
# Round gable window
for x in (X0, X1):
    pane(x, 12, 13); pane(x, 13, 13)

# Roof trusses with lanterns hanging over the landing and rooms
for x in (8, 12, 16):
    for z in range(9, 18):
        put(x, 12, z, "dark_oak_log", axis="z")
    lantern(x, 11, 13, hanging=True)

# --- Dormers on the street side -----------------------------------------------------------------------------
for xc in (7, 17):
    for y in (11, 12):
        for z in (J0, J0 + 1, J0 + 2):
            put(xc - 1, y, z, "white_terracotta"); put(xc + 1, y, z, "white_terracotta")
            put(xc, y, z, "air")
        put(xc - 1, y, J0, "dark_oak_log", axis="y"); put(xc + 1, y, J0, "dark_oak_log", axis="y")
        pane(xc, y, J0)
    for z in range(J0 - 1, J0 + 3):
        stair(xc - 1, 13, z, "deepslate_tile_stairs", "east")
        stair(xc + 1, 13, z, "deepslate_tile_stairs", "west")
        put(xc, 13, z, "deepslate_tiles")
        slab(xc, 14, z, "deepslate_tile_slab")
    # the dormer's window ledge sits on the main eave
    stair(xc, 10, J0 - 1, "deepslate_tile_stairs", "south")

# =====================================================================================================
# Inside, ground floor
# =====================================================================================================
# Inglenook fireplace in the west gable, with the chimney outside
for y in range(1, 5):
    put(X0, y, 12, "stone_bricks"); put(X0, y, 14, "stone_bricks")
put(X0, 1, 13, "campfire", facing="east", lit=True, signal_fire=False, waterlogged=False)
for y in range(2, 5):
    put(X0, y, 13, "stone_bricks")
stair(X0 + 1, 3, 12, "stone_brick_stairs", "west", top=True)
stair(X0 + 1, 3, 14, "stone_brick_stairs", "west", top=True)
stair(X0 + 1, 4, 13, "stone_brick_stairs", "west", top=True)
put(X0 + 1, 4, 12, "candle", candles=3, lit=True, waterlogged=False)
put(X0 + 1, 4, 14, "candle", candles=2, lit=True, waterlogged=False)
for z in (12, 13, 14):
    put(X0 + 1, 1, z, "red_carpet")
    put(X0 + 2, 1, z, "red_carpet")
stair(X0 + 3, 1, 12, "spruce_stairs", "east")   # armchairs facing the fire
stair(X0 + 3, 1, 14, "spruce_stairs", "east")
put(X0 + 3, 1, 13, "barrel", facing="up", open=False)
potted(X0 + 3, 2, 13)
for y in range(0, 19):
    for x in (1, 2, 3):
        for z in (12, 13, 14):
            if y < 18 or (x, z) != (2, 13):
                stone(x, y, z)
put(2, 18, 13, "campfire", facing="north", lit=True, signal_fire=False, waterlogged=False)  # chimney smoke
for x, z in [(1, 12), (3, 12), (1, 14), (3, 14), (1, 13), (3, 13), (2, 12), (2, 14)]:
    put(x, 19, z, "cobblestone_wall")

# Long table in the middle with benches
for x in range(9, 14):
    slab(x, 1, 11, "dark_oak_slab", top=True)
    stair(x, 1, 10, "spruce_stairs", "north")
    stair(x, 1, 12, "spruce_stairs", "south")
put(9, 2, 11, "candle", candles=4, lit=True, waterlogged=False)
lantern(13, 2, 11)
put(11, 2, 11, "cake", bites=2)


def table(x, z):
    fence(x, 1, z, "dark_oak_fence")
    put(x, 2, z, "dark_oak_pressure_plate", powered=False)
    stair(x - 1, 1, z, "spruce_stairs", "west")
    stair(x + 1, 1, z, "spruce_stairs", "east")


table(9, 15)
table(13, 15)

# The bar: a counter with the bell on it, stools in front, the innkeeper's spot behind
for z in range(11, 16):
    put(16, 1, z, "barrel", facing="up", open=False)
    stair(15, 1, z, "spruce_stairs", "west")  # stools; they also keep the innkeeper behind the counter
put(16, 2, 13, "bell", attachment="floor", facing="west", powered=False)
lantern(16, 2, 11)
put(16, 2, 15, "brewing_stand", has_bottle_0=False, has_bottle_1=False, has_bottle_2=False)
potted(16, 2, 12)
put(16, 2, 14, "candle", candles=2, lit=True, waterlogged=False)
for z in (12, 14):
    lantern(16, 4, z, hanging=True)
# kegs along the east wall, a smoker and a water cauldron in the kitchen corner
for z in range(10, 14):
    put(19, 1, z, "barrel", facing="west", open=False)
    put(19, 2, z, "barrel", facing="west", open=False)
    put(19, 3, z, "spruce_slab", type="bottom", waterlogged=False)
put(19, 4, 11, "flower_pot")
put(19, 1, 9, "smoker", facing="west", lit=True)
put(18, 1, 9, "water_cauldron", level=3)
put(17, 1, 9, "crafting_table")
put(19, 1, 14, "composter", level=0)
put(19, 1, 17, "barrel", facing="up", open=False)
put(19, 2, 17, "barrel", facing="up", open=False)

# Stairs to the upper floor along the back wall, rising westward
for i, (x, y) in enumerate([(9, 1), (8, 2), (7, 3), (6, 4)]):
    stair(x, y, 17, "spruce_stairs", "west")
    for below in range(1, y):
        put(x, below, 17, "bookshelf" if below == 1 else "spruce_planks")
air(6, 5, 17, 9, 5, 17)
for x in range(6, 10):
    fence(x, 6, 16)  # railing upstairs around the stairwell
fence(10, 6, 17)

# Lanterns and a guest book by the door
for x, z in [(7, 10), (11, 13), (7, 16)]:
    lantern(x, 4, z, hanging=True)
put(14, 1, 9, "lectern", facing="south", has_book=False, powered=False)
put(9, 1, 9, "barrel", facing="up", open=False)
potted(9, 2, 9)

# =====================================================================================================
# Inside, upper floor: a corridor along z 13, rooms north and south, the landing in the south-west.
# =====================================================================================================
def partition(x1, z1, x2, z2):
    fill(x1, 6, z1, x2, 9, z2, "spruce_planks")


partition(5, 12, 19, 12)      # north rooms / corridor
partition(10, 14, 19, 14)     # south rooms / corridor
partition(10, 8, 10, 11); partition(15, 8, 15, 11)
partition(10, 15, 10, 18); partition(15, 15, 15, 18)
for x in (7, 12, 17):
    door(x, 12, 6, "south")
door(12, 14, 6, "north")
door(17, 14, 6, "north")

# North rooms: two cosy rooms and a room with two beds for travelling companions
bed(6, 6, 9, "red", "north")
put(5, 6, 9, "barrel", facing="up", open=False); lantern(5, 7, 9)
put(9, 6, 8, "flower_pot")
bed(13, 6, 9, "blue", "north")
put(14, 6, 8, "barrel", facing="up", open=False); lantern(14, 7, 8)
put(11, 6, 11, "chest", facing="east", type="single", waterlogged=False)
bed(16, 6, 9, "green", "north")
bed(19, 6, 9, "lime", "north")
put(18, 6, 8, "barrel", facing="up", open=False); potted(18, 7, 8)
put(17, 6, 11, "white_carpet"); put(18, 6, 11, "white_carpet")

# South room: the map room, for anyone planning a journey
bed(14, 6, 18, "yellow", "north")
put(11, 6, 18, "cartography_table")
put(11, 6, 17, "bookshelf"); put(11, 7, 17, "bookshelf"); put(11, 6, 15, "bookshelf")
put(13, 6, 18, "barrel", facing="up", open=False); lantern(13, 7, 18)

# South-east: the innkeeper's room, with the strongbox and the door to the tower
bed(18, 6, 18, "brown", "north")
chest(19, 6, 18, "west", "bloodline:chests/roadside_inn")
put(16, 6, 18, "lectern", facing="east", has_book=False, powered=False)
put(16, 6, 15, "bookshelf"); put(16, 7, 15, "bookshelf")
lantern(19, 6, 15)
for z in range(15, 18):
    put(17, 6, z, "brown_carpet")

# Landing: a bench and a plant
stair(8, 6, 15, "spruce_stairs", "south")
stair(9, 6, 15, "spruce_stairs", "south")
potted(5, 6, 18)

# =====================================================================================================
# The lantern tower (x 21..25, z 15..19) behind the bar; its signal fire smokes above the treetops
# =====================================================================================================
T0x, T1x, T0z, T1z, TOP = 21, 25, 15, 19, 20
for y in range(0, TOP + 1):
    for x in range(T0x, T1x + 1):
        for z in range(T0z, T1z + 1):
            edge = x in (T0x, T1x) or z in (T0z, T1z)
            corner = x in (T0x, T1x) and z in (T0z, T1z)
            if y == 0 or y == TOP or edge:
                if corner:
                    put(x, y, z, "stone_bricks")
                else:
                    stone(x, y, z)
            else:
                put(x, y, z, "air")
for y in range(1, TOP + 1):
    put(24, y, 17, "ladder", facing="west", waterlogged=False)
put(24, TOP, 17, "spruce_trapdoor", facing="west", half="bottom", open=False, waterlogged=False, powered=False)
# doorways from behind the bar and from the innkeeper's room
air(20, 1, 16, 21, 2, 16)
air(20, 6, 16, 21, 7, 16)
# arrow slits
for y in (5, 9, 13, 17):
    put(23, y, T0z, "air"); put(23, y, T1z, "air"); put(T1x, y, 17, "air")
    put(23, y + 1, T0z, "air"); put(23, y + 1, T1z, "air"); put(T1x, y + 1, 17, "air")
# a band of stone brick stairs under the parapet
for x in range(T0x - 1, T1x + 2):
    stair(x, TOP - 1, T0z - 1, "stone_brick_stairs", "south", top=True)
    stair(x, TOP - 1, T1z + 1, "stone_brick_stairs", "north", top=True)
for z in range(T0z, T1z + 1):
    stair(T0x - 1, TOP - 1, z, "stone_brick_stairs", "east", top=True)
    stair(T1x + 1, TOP - 1, z, "stone_brick_stairs", "west", top=True)
# the parapet: wider than the tower, with battlements and lanterns on the corners
for x in range(T0x - 1, T1x + 2):
    for z in range(T0z - 1, T1z + 2):
        if x in (T0x - 1, T1x + 1) or z in (T0z - 1, T1z + 1):
            put(x, TOP, z, "stone_bricks")
            if (x + z) % 2 == 0:
                put(x, TOP + 1, z, "stone_brick_wall")
for x, z in [(T0x - 1, T0z - 1), (T1x + 1, T0z - 1), (T0x - 1, T1z + 1), (T1x + 1, T1z + 1)]:
    put(x, TOP + 1, z, "stone_brick_wall")
    lantern(x, TOP + 2, z)
# the signal fire
put(22, TOP + 1, 16, "hay_block", axis="y")
put(22, TOP + 2, 16, "campfire", facing="south", lit=True, signal_fire=True, waterlogged=False)
put(22, TOP + 1, 18, "barrel", facing="up", open=False)
# floors at the upper storey and above, the ladder passing through them, a lantern on each
for y in (5, 11, 17):
    fill(22, y, 16, 24, y, 18, "spruce_planks")
    put(24, y, 17, "ladder", facing="west", waterlogged=False)
    lantern(22, y + 1, 18)
put(23, 12, 18, "barrel", facing="up", open=False)
put(22, 1, 18, "barrel", facing="up", open=False)
put(22, 18, 16, "chest", facing="east", type="single", waterlogged=False)

# =====================================================================================================
# The stable (x 21..29, z 8..14) against the hall's east wall, open to the road
# =====================================================================================================
for x in range(21, 30):
    for z in range(8, 15):
        put(x, 0, z, "coarse_dirt" if rng.random() < 0.4 else "dirt_path")
    for z in range(9, 15):
        air(x, 1, z, x, 5, z)
for z in range(8, 15):
    for y in range(1, 5):
        put(29, y, z, "spruce_planks")
for x in range(21, 30):
    for y in range(1, 5):
        put(x, y, 14, "spruce_planks")
for x, z in [(21, 8), (25, 8), (29, 8), (25, 14), (29, 14), (29, 11)]:
    for y in range(1, 5):
        put(x, y, z, "spruce_log", axis="y")
for z in (10, 12):
    pane(29, 2, z)
# stall divider and the open front with gates
for z in range(9, 14):
    fence(25, 1, z)
for x in (22, 23, 24, 26, 27, 28):
    fence(x, 1, 8)
put(23, 1, 8, "spruce_fence_gate", facing="north", in_wall=False, open=False, powered=False)
put(27, 1, 8, "spruce_fence_gate", facing="north", in_wall=False, open=False, powered=False)
# lean-to roof: half slabs stepping down from the hall to the east
for z in range(7, 16):
    for x, y, top in [(21, 5, True), (22, 5, True), (23, 5, False), (24, 5, False), (25, 4, True),
                      (26, 4, True), (27, 4, False), (28, 4, False), (29, 3, True), (30, 3, True)]:
        if z == 15 and x <= 25:
            continue  # the tower stands there
        slab(x, y, z, "spruce_slab", top=top)
# hay, a trough and a tack chest
for x, z in [(22, 13), (23, 13), (22, 12), (28, 13)]:
    put(x, 1, z, "hay_block", axis="x" if z == 13 else "z")
put(22, 2, 13, "hay_block", axis="y")
put(24, 1, 13, "water_cauldron", level=3)
put(26, 1, 13, "water_cauldron", level=3)
chest(27, 1, 13, "north", "bloodline:chests/roadside_inn_stable")
lantern(23, 4, 11, hanging=True)
lantern(27, 3, 11, hanging=True)
entities.append(((23.5, 1.0, 10.5), (23, 1, 10), {"id": "minecraft:horse", "PersistenceRequired": 1}))
entities.append(((27.5, 1.0, 10.5), (27, 1, 10), {"id": "minecraft:donkey", "PersistenceRequired": 1}))

# =====================================================================================================
# Out front: the road path, porch, well, signpost, lamp posts and flower beds
# =====================================================================================================
for z in range(0, 8):
    for x in (11, 12):
        put(x, 0, z, "dirt_path" if rng.random() < 0.7 else "gravel")
    if rng.random() < 0.5:
        put(10 if z % 2 else 13, 0, z, "dirt_path")
# path along the front to the stable
for x in range(13, 30):
    put(x, 0, 6, "dirt_path" if rng.random() < 0.75 else "coarse_dirt")
    put(x, 0, 7, "dirt_path" if rng.random() < 0.5 else "coarse_dirt")

# porch roof over the door, on two posts
for x in range(10, 14):
    for z in (5, 6):
        slab(x, 4, z, "deepslate_tile_slab", top=True)
for x in (10, 13):
    for y in (1, 2, 3):
        fence(x, y, 5, "dark_oak_fence")
lantern(11, 3, 6, hanging=True); lantern(12, 3, 6, hanging=True)

# flower beds along the front wall
for x in list(range(5, 10)) + list(range(14, 20)):
    put(x, 0, 7, "grass_block", snowy=False)
    flower(x, 1, 7)

# the well
for x in range(4, 9):
    for z in range(1, 6):
        edge = x in (4, 8) or z in (1, 5)
        put(x, 0, z, "cobblestone" if edge else "stone_bricks")
for x in range(5, 8):
    for z in range(2, 5):
        if (x, z) == (6, 3):
            continue
        stone(x, 1, z)
put(6, 0, 3, "water", level=0)
put(6, 1, 3, "water", level=0)
for x, z in [(5, 2), (7, 2), (5, 4), (7, 4)]:
    fence(x, 2, z, "spruce_fence"); fence(x, 3, z, "spruce_fence")
for x in range(5, 8):
    stair(x, 4, 2, "spruce_stairs", "south")
    stair(x, 4, 4, "spruce_stairs", "north")
    put(x, 4, 3, "spruce_planks")
    slab(x, 5, 3, "spruce_slab")
lantern(6, 3, 3, hanging=True)
put(8, 1, 1, "cauldron")

# the inn's signpost by the road, with a hanging sign
for y in range(1, 6):
    put(15, y, 1, "dark_oak_log", axis="y")
fence(16, 5, 1, "dark_oak_fence"); fence(17, 5, 1, "dark_oak_fence")
put(16, 4, 1, "spruce_hanging_sign", rotation=0, attached=True, waterlogged=False)
SIGN_TEXT = {"messages": ["", "~ INN ~", "Beds · Ale · Work", ""], "color": "black", "has_glowing_text": 0}
block_nbt[(16, 4, 1)] = {"id": "minecraft:hanging_sign", "front_text": SIGN_TEXT, "back_text": SIGN_TEXT, "is_waxed": 1}
lantern(17, 4, 1, hanging=True)
put(15, 6, 1, "lantern", hanging=False, waterlogged=False)

# lamp posts along the path
for x, z in [(10, 2), (13, 2)]:
    fence(x, 1, z, "dark_oak_fence"); fence(x, 2, z, "dark_oak_fence")
    lantern(x, 3, z)

# a bench under the jetty and a hitching post by the stable
for x in (17, 18):
    stair(x, 1, 6, "spruce_stairs", "north")
fence(20, 1, 5, "spruce_fence"); fence(20, 2, 5, "spruce_fence")

# =====================================================================================================
# Behind: a kitchen garden with crops, a water channel and a scarecrow
# =====================================================================================================
for x in range(11, 14):
    put(x, 0, 19, "dirt_path")
put(12, 0, 20, "dirt_path")
for x in range(X0, 19):
    for z in range(20, 28):
        if x in (X0, 18) or z in (20, 27):
            fence(x, 1, z)
            put(x, 0, z, "grass_block", snowy=False)
put(12, 1, 20, "spruce_fence_gate", facing="south", in_wall=False, open=False, powered=False)
for x in range(6, 17):
    for z, crop, age in [(21, "beetroots", 3), (22, "wheat", 7), (24, "carrots", 7), (25, "potatoes", 7)]:
        put(x, 0, z, "farmland", moisture=7)
        put(x, 1, z, crop, age=age)
    put(x, 0, 23, "water", level=0)
    put(x, 1, 23, "air")
for z in range(21, 27):
    put(5, 0, z, "dirt_path"); put(17, 0, z, "dirt_path")
for x in range(6, 17):
    put(x, 0, 26, "grass_block", snowy=False)
# the scarecrow
fence(11, 1, 26)
put(11, 2, 26, "hay_block", axis="y")
put(11, 3, 26, "carved_pumpkin", facing="north")
fence(10, 2, 26); fence(12, 2, 26)
put(7, 1, 26, "composter", level=4)
put(15, 1, 26, "barrel", facing="up", open=False)
put(16, 1, 26, "flower_pot")

# the inn cat, asleep by the fire
entities.append(((7.5, 1.0, 13.5), (7, 1, 13), {"id": "minecraft:cat", "PersistenceRequired": 1}))

# =====================================================================================================
# Connect fences, panes and walls to their neighbours
# =====================================================================================================
SOLID_SUFFIXES = ("planks", "_log", "bricks", "cobblestone", "terracotta", "tiles", "stone")


def is_solid(name):
    return name.endswith(SOLID_SUFFIXES) or name in ("barrel", "bookshelf", "hay_block", "smoker", "crafting_table")


def neighbours(x, y, z):
    return {"north": (x, y, z - 1), "south": (x, y, z + 1), "east": (x + 1, y, z), "west": (x - 1, y, z)}


for pos, (name, props) in list(blocks.items()):
    if name.endswith("_fence") or name == "glass_pane" or name.endswith("_wall"):
        family = "_fence" if name.endswith("_fence") else "pane" if name == "glass_pane" else "_wall"
        new = {}
        for side, npos in neighbours(*pos).items():
            n = blocks.get(npos, ("air", {}))[0]
            joins = is_solid(n) or n.endswith(family) or (family == "_fence" and n.endswith("_fence_gate"))
            if family == "_wall":
                new[side] = "low" if joins else "none"
            else:
                new[side] = "true" if joins else "false"
        if family == "_wall":
            new["up"] = "true"
        new["waterlogged"] = "false"
        blocks[pos] = (name, new)

# =====================================================================================================
# Write the NBT
# =====================================================================================================
def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag(t, name, payload):
    return bytes([t]) + nbt_string(name) + payload


def int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)


def double_list(values):
    return bytes([6]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">d", v) for v in values)


def compound(entries):
    return b"".join(entries) + b"\x00"


def value(name, v):
    """A python value as a named NBT tag: dicts are compounds, lists of strings are string lists."""
    if isinstance(v, dict):
        return tag(10, name, compound([value(k, x) for k, x in v.items()]))
    if isinstance(v, list):
        return tag(9, name, bytes([8]) + struct.pack(">i", len(v)) + b"".join(nbt_string(s) for s in v))
    if isinstance(v, int):
        return tag(1, name, struct.pack(">b", v)) if name in ("PersistenceRequired", "is_waxed", "has_glowing_text") else tag(3, name, struct.pack(">i", v))
    return tag(8, name, nbt_string(str(v)))


palette = []
index = {}
block_entries = []
for pos in sorted(blocks, key=lambda p: (p[1], p[2], p[0])):
    name, props = blocks[pos]
    key = (name, tuple(sorted(props.items())))
    if key not in index:
        index[key] = len(palette)
        palette.append(key)
    fields = [tag(9, "pos", int_list(list(pos))), tag(3, "state", struct.pack(">i", index[key]))]
    if pos in block_nbt:
        fields.append(value("nbt", block_nbt[pos]))
    block_entries.append(compound(fields))

palette_entries = []
for name, props in palette:
    fields = [tag(8, "id", nbt_string("minecraft:" + name))]
    if props:
        fields.append(tag(10, "properties", compound([tag(8, k, nbt_string(v)) for k, v in props])))
    palette_entries.append(compound(fields))

entity_entries = []
for pos, block_pos, nbt in entities:
    entity_entries.append(compound([
        tag(9, "pos", double_list(list(pos))),
        tag(9, "blockPos", int_list(list(block_pos))),
        value("nbt", nbt),
    ]))

for p in blocks:
    assert all(0 <= p[i] < SIZE[i] for i in range(3)), f"block outside the structure: {p}"

root = compound([
    tag(9, "size", int_list(list(SIZE))),
    tag(9, "entities", bytes([10]) + struct.pack(">i", len(entity_entries)) + b"".join(entity_entries)),
    tag(9, "blocks", bytes([10]) + struct.pack(">i", len(block_entries)) + b"".join(block_entries)),
    tag(9, "palette", bytes([10]) + struct.pack(">i", len(palette_entries)) + b"".join(palette_entries)),
    tag(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
])
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wb") as f:
    f.write(bytes([10]) + nbt_string("") + root)
print(f"Wrote {len(block_entries)} blocks, {len(palette)} block states, {len(entity_entries)} entities to {os.path.normpath(OUT)}")
