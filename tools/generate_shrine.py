#!/usr/bin/env python3
"""
Generates the wayside shrines: small stone chapels along the way, in three styles for different lands.

    python3 tools/generate_shrine.py

Writes src/main/resources/data/bloodline/structure/shrine/{temperate,cold,warm}.nbt.

Layout (9 x 9 x 9, y = 0 is ground level, the open front faces south / +z):

    at the back, a canopy with a steep roof over a weathered statue and an altar with unlit candles,
    a lantern hanging over the altar and a traveller's barrel; in front, a small open courtyard with
    low walls, lanterns on the corner posts, two benches, flowers, and a step out front.

Lighting the altar candles is how a player asks an ancestor's blessing (see Shrines.java).
"""
import os
import random

from structure_writer import Template

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "bloodline", "structure", "shrine")

STYLES = {
    # Meadows and forests: stone bricks gone mossy, dark oak roof, white candles.
    "temperate": dict(
        floor=["stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks"],
        wall=["stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks"],
        low_wall="stone_brick_wall", stairs="stone_brick_stairs", post="oak_log", planks="oak_planks",
        roof="dark_oak_stairs", ridge="dark_oak_slab", bench="oak_stairs", trim="chiseled_stone_bricks",
        statue=("andesite_wall", "polished_andesite"), candle="white_candle", flower="potted_poppy", flower2="potted_cornflower"),
    # Taiga and snow: cobblestone, spruce, plain candles.
    "cold": dict(
        floor=["cobblestone", "cobblestone", "mossy_cobblestone", "stone"],
        wall=["cobblestone", "cobblestone", "mossy_cobblestone"],
        low_wall="cobblestone_wall", stairs="cobblestone_stairs", post="spruce_log", planks="spruce_planks",
        roof="spruce_stairs", ridge="spruce_slab", bench="spruce_stairs", trim="chiseled_stone_bricks",
        statue=("cobblestone_wall", "polished_andesite"), candle="candle", flower="potted_spruce_sapling", flower2="potted_fern"),
    # Savanna and desert: sandstone, acacia, orange candles.
    "warm": dict(
        floor=["smooth_sandstone", "smooth_sandstone", "cut_sandstone", "sandstone"],
        wall=["cut_sandstone", "sandstone", "smooth_sandstone"],
        low_wall="sandstone_wall", stairs="sandstone_stairs", post="acacia_log", planks="acacia_planks",
        roof="acacia_stairs", ridge="acacia_slab", bench="acacia_stairs", trim="chiseled_sandstone",
        statue=("sandstone_wall", "chiseled_sandstone"), candle="orange_candle", flower="potted_dead_bush", flower2="potted_cactus"),
}


def build(name, s, rng):
    t = Template((9, 9, 9))
    pick = lambda options: rng.choice(options)

    # Floor, and the step out front.
    for x in range(1, 8):
        for z in range(1, 9):
            if z == 8 and x in (3, 4, 5):
                t.stair(x, 0, z, s["stairs"], "north")
            elif z < 8 or x in (1, 7):
                t.put(x, 0, z, pick(s["floor"]))

    # --- The canopy at the back (z = 1 to 4): back wall, posts and a steep roof over the statue and the altar.
    for x in range(1, 8):
        for y in range(1, 4):
            t.put(x, y, 1, pick(s["wall"]))
    for x in (1, 7):
        for z in (1, 4):
            for y in range(1, 4):
                t.put(x, y, z, s["post"], axis="y")
    for z in (2, 3):
        t.put(1, 1, z, s["low_wall"])
        t.put(7, 1, z, s["low_wall"])
    # The statue stands in front of the back wall, under the roof.
    t.put(4, 1, 2, s["trim"])
    t.put(4, 2, 2, s["statue"][0])
    t.put(4, 3, 2, s["statue"][1])
    t.put(4, 3, 1, s["trim"])
    # The altar, with unlit candles on it.
    for x in (3, 4, 5):
        t.put(x, 1, 3, s["trim"])
    t.put(3, 2, 3, s["candle"], candles=2, lit=False, waterlogged=False)
    t.put(4, 2, 3, s["candle"], candles=4, lit=False, waterlogged=False)
    t.put(5, 2, 3, s["candle"], candles=1, lit=False, waterlogged=False)
    # The roof: a gable from z = 0 to 5, ridge running front to back, open at the front.
    for z in range(0, 6):
        for step in range(4):
            t.stair(step, 4 + step, z, s["roof"], "east")
            t.stair(8 - step, 4 + step, z, s["roof"], "west")
        t.slab(4, 8, z, s["ridge"])
    for y in range(4, 8):
        for x in range(y - 3, 12 - y):
            t.put(x, y, 1, s["planks"])
    # Beams under the roof at the front, with the lantern hanging over the altar.
    for x in range(2, 7):
        t.put(x, 4, 4, s["planks"])
    t.put(4, 3, 4, "lantern", hanging=True, waterlogged=False)
    t.put(6, 1, 2, "barrel", nbt={"id": "minecraft:barrel", "LootTable": "bloodline:chests/shrine"}, facing="up", open=False)
    t.put(2, 1, 2, s["flower"])

    # --- The courtyard in front (z = 5 to 7): low walls, lanterns on the corner posts, a bench and flowers.
    for z in range(5, 8):
        t.put(1, 1, z, s["low_wall"])
        t.put(7, 1, z, s["low_wall"])
    for x in (1, 7):
        t.put(x, 1, 8, s["post"], axis="y")
        t.put(x, 2, 8, "lantern", hanging=False, waterlogged=False)
    t.put(2, 1, 8, s["low_wall"])
    t.put(6, 1, 8, s["low_wall"])
    t.stair(2, 1, 6, s["bench"], "east")
    t.stair(6, 1, 6, s["bench"], "west")
    t.put(2, 1, 7, s["flower2"])
    t.put(6, 1, 7, s["flower"])

    t.connect()
    count = t.write(os.path.join(OUT, name + ".nbt"))
    print(f"{name}: {count} blocks")


if __name__ == "__main__":
    for i, (name, style) in enumerate(STYLES.items()):
        build(name, style, random.Random(4021 + i))
