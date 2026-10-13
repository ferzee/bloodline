#!/usr/bin/env python3
"""
Generates the roadside inn's structure file (src/main/resources/data/bloodline/structure/roadside_inn.nbt).

Run it after changing the building:  python3 tools/generate_inn.py
A builder can also replace the .nbt with one saved in-game with a structure block; the mod
doesn't care how the file was made, as long as it keeps a bell inside (that's where the innkeeper appears).

Coordinates: x 0..12 (west to east), y 0..15 (up), z 0..10. The entrance faces north (z = 0).
"""
import gzip
import os
import struct

DATA_VERSION = 5023  # Minecraft 26.3
SIZE = (13, 16, 11)
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "bloodline", "structure", "roadside_inn.nbt")

blocks = {}


def put(x, y, z, block, **props):
    blocks[(x, y, z)] = (block, tuple(sorted((k, str(v).lower()) for k, v in props.items())))


def fill(x1, y1, z1, x2, y2, z2, block, **props):
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                put(x, y, z, block, **props)


W, D = 12, 10  # last x and z

# --- Foundation and ground floor -----------------------------------------------------------------
fill(0, 0, 0, W, 0, D, "spruce_planks")
for x in range(0, W + 1):
    put(x, 0, 0, "cobblestone"); put(x, 0, D, "cobblestone")
for z in range(0, D + 1):
    put(0, 0, z, "cobblestone"); put(W, 0, z, "cobblestone")

def walls(y1, y2, wall, window_y):
    fill(1, y1, 1, W - 1, y2, D - 1, "air")
    for y in range(y1, y2 + 1):
        for x in range(0, W + 1):
            put(x, y, 0, wall); put(x, y, D, wall)
        for z in range(0, D + 1):
            put(0, y, z, wall); put(W, y, z, wall)
        for x, z in [(0, 0), (W, 0), (0, D), (W, D), (0, 5), (W, 5)]:
            put(x, y, z, "dark_oak_log", axis="y")
    for y in window_y:
        for x in (2, 3, 9, 10):
            put(x, y, 0, "glass_pane", north=False, south=False, east=True, west=True, waterlogged=False)
            put(x, y, D, "glass_pane", north=False, south=False, east=True, west=True, waterlogged=False)
        for z in (2, 3, 7, 8):
            put(0, y, z, "glass_pane", north=True, south=True, east=False, west=False, waterlogged=False)
            put(W, y, z, "glass_pane", north=True, south=True, east=False, west=False, waterlogged=False)

walls(1, 4, "spruce_planks", (2, 3))
# Front door
put(6, 1, 0, "oak_door", facing="south", half="lower", hinge="left", open=False, powered=False)
put(6, 2, 0, "oak_door", facing="south", half="upper", hinge="left", open=False, powered=False)
put(6, 3, 0, "spruce_planks")

# --- Upper floor (y 5) and walls (y 6..8) --------------------------------------------------------
fill(1, 5, 1, W - 1, 5, D - 1, "spruce_planks")
for x in range(0, W + 1):
    put(x, 5, 0, "dark_oak_log", axis="x"); put(x, 5, D, "dark_oak_log", axis="x")
for z in range(1, D):
    put(0, 5, z, "dark_oak_log", axis="z"); put(W, 5, z, "dark_oak_log", axis="z")
walls(6, 8, "oak_planks", (7,))

# --- Stairs to the upper floor along the east wall --------------------------------------------------
for i, (y, z) in enumerate([(1, 6), (2, 5), (3, 4), (4, 3), (5, 2)]):
    put(W - 1, y, z, "spruce_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    for below in range(1, y):
        put(W - 1, below, z, "spruce_planks")
for z in (3, 4, 5, 6):
    put(W - 1, 5, z, "air")

# --- The bar: a counter of barrels with the bell on it; the innkeeper stands behind it --------------
for x in range(5, 10):
    put(x, 1, 7, "barrel", facing="up", open=False)
put(7, 2, 7, "bell", attachment="floor", facing="north", powered=False)
put(5, 2, 7, "lantern", hanging=False, waterlogged=False)
put(9, 2, 7, "lantern", hanging=False, waterlogged=False)
for x in (5, 6, 9):
    put(x, 1, 9, "barrel", facing="north", open=False)
put(10, 1, 9, "brewing_stand", has_bottle_0=False, has_bottle_1=False, has_bottle_2=False)

# --- Fireplace with a chimney through the back wall and roof ---------------------------------------
put(2, 1, 9, "campfire", facing="north", lit=True, signal_fire=False, waterlogged=False)
put(1, 1, 9, "cobblestone"); put(3, 1, 9, "cobblestone")
fill(1, 2, 9, 3, 2, 9, "cobblestone")
put(2, 3, 9, "lantern", hanging=False, waterlogged=False)
fill(2, 1, D, 2, 15, D, "cobblestone")

# --- Tables and chairs -------------------------------------------------------------------------------
def table(x, z):
    put(x, 1, z, "oak_fence", north=False, south=False, east=False, west=False, waterlogged=False)
    put(x, 2, z, "oak_pressure_plate", powered=False)
    put(x - 1, 1, z, "oak_stairs", facing="west", half="bottom", shape="straight", waterlogged=False)
    put(x + 1, 1, z, "oak_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)

table(3, 3)
table(3, 6)
table(8, 3)

# Hanging lanterns under the upper floor
for x, z in [(3, 4), (8, 4), (6, 7)]:
    put(x, 4, z, "lantern", hanging=True, waterlogged=False)

# --- Guest beds upstairs -----------------------------------------------------------------------------
for x, colour in [(2, "red"), (5, "blue"), (8, "green")]:
    put(x, 6, 8, f"{colour}_bed", facing="south", part="foot", occupied=False)
    put(x, 6, 9, f"{colour}_bed", facing="south", part="head", occupied=False)
    put(x + 1, 6, 9, "barrel", facing="up", open=False)
put(1, 6, 1, "lantern", hanging=False, waterlogged=False)
put(9, 6, 1, "lantern", hanging=False, waterlogged=False)

# --- Gabled roof (ridge along x) ----------------------------------------------------------------------
for k in range(0, 5):
    y = 9 + k
    for x in range(0, W + 1):
        put(x, y, k, "dark_oak_stairs", facing="south", half="bottom", shape="straight", waterlogged=False)
        put(x, y, D - k, "dark_oak_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    # Gable ends and the attic between them
    for z in range(k + 1, D - k):
        put(0, y, z, "spruce_planks"); put(W, y, z, "spruce_planks")
        for x in range(1, W):
            put(x, y, z, "air")
for x in range(0, W + 1):
    put(x, 14, 5, "dark_oak_slab", type="bottom", waterlogged=False)
fill(2, 9, D, 2, 15, D, "cobblestone")  # the chimney stays above the roof

# --- Write the NBT ------------------------------------------------------------------------------------
def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b

def tag(t, name, payload):
    return bytes([t]) + nbt_string(name) + payload

def int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)

def compound(entries):
    return b"".join(entries) + b"\x00"

palette = []
index = {}
block_entries = []
for pos in sorted(blocks, key=lambda p: (p[1], p[2], p[0])):
    key = blocks[pos]
    if key not in index:
        index[key] = len(palette)
        palette.append(key)
    block_entries.append(compound([tag(9, "pos", int_list(list(pos))), tag(3, "state", struct.pack(">i", index[key]))]))

palette_entries = []
for name, props in palette:
    fields = [tag(8, "id", nbt_string("minecraft:" + name))]
    if props:
        fields.append(tag(10, "properties", compound([tag(8, k, nbt_string(v)) for k, v in props])))
    palette_entries.append(compound(fields))

root = compound([
    tag(9, "size", int_list(list(SIZE))),
    tag(9, "entities", bytes([10]) + struct.pack(">i", 0)),
    tag(9, "blocks", bytes([10]) + struct.pack(">i", len(block_entries)) + b"".join(block_entries)),
    tag(9, "palette", bytes([10]) + struct.pack(">i", len(palette_entries)) + b"".join(palette_entries)),
    tag(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
])
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wb") as f:
    f.write(bytes([10]) + nbt_string("") + root)
print(f"Wrote {len(block_entries)} blocks, {len(palette)} block states to {os.path.normpath(OUT)}")
