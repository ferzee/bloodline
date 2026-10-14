"""
A small helper for writing Minecraft structure template (.nbt) files from Python.

    t = Template((7, 6, 9))
    t.fill(0, 0, 0, 6, 5, 8, "deepslate_bricks")
    t.put(3, 1, 0, "air")
    t.jigsaw(3, 1, 0, "north", pool="bloodline:crypt/halls")
    t.write("src/main/resources/data/bloodline/structure/crypt/corridor.nbt")

Used by generate_crypt.py. Minecraft 26.3 palette entries use the keys "id" and "properties".
"""
import gzip
import os
import struct

DATA_VERSION = 5023  # Minecraft 26.3

FACINGS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


class Template:
    def __init__(self, size):
        self.size = size
        self.blocks = {}
        self.nbt = {}
        self.entities = []

    # --- Placing blocks ---------------------------------------------------------------------------
    def put(self, x, y, z, block, nbt=None, **props):
        self.blocks[(x, y, z)] = (block, {k: str(v).lower() for k, v in props.items()})
        if nbt is None:
            self.nbt.pop((x, y, z), None)
        else:
            self.nbt[(x, y, z)] = nbt

    def fill(self, x1, y1, z1, x2, y2, z2, block, **props):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.put(x, y, z, block, **props)

    def air(self, x1, y1, z1, x2, y2, z2):
        self.fill(x1, y1, z1, x2, y2, z2, "air")

    def get(self, x, y, z):
        return self.blocks.get((x, y, z), ("structure_void", {}))[0]

    def stair(self, x, y, z, block, facing, top=False):
        self.put(x, y, z, block, facing=facing, half="top" if top else "bottom", shape="straight", waterlogged=False)

    def slab(self, x, y, z, block, top=False):
        self.put(x, y, z, block, type="top" if top else "bottom", waterlogged=False)

    def chest(self, x, y, z, facing, loot):
        self.put(x, y, z, "chest", nbt={"id": "minecraft:chest", "LootTable": loot}, facing=facing, type="single", waterlogged=False)

    def jigsaw(self, x, y, z, facing, pool, final_state="minecraft:air", name="bloodline:crypt_door"):
        """A connector on the piece's edge, pointing out of it. Its pool supplies the piece that attaches here."""
        self.put(x, y, z, "jigsaw", nbt={
            "id": "minecraft:jigsaw", "name": name, "target": name, "pool": pool, "final_state": final_state,
            "joint": "aligned", "placement_priority": 0, "selection_priority": 0,
        }, orientation=f"{facing}_up")

    # --- Neighbour-dependent shapes ---------------------------------------------------------------
    def connect(self):
        """Joins fences, panes, iron bars and walls to their neighbours, like the game does when you place them."""
        def solid(name):
            return name.endswith(("bricks", "tiles", "planks", "_log", "stone", "deepslate", "blackstone", "_block", "cobblestone")) \
                or name in ("chiseled_deepslate", "polished_deepslate", "gilded_blackstone", "tuff", "calcite")
        for pos, (name, props) in list(self.blocks.items()):
            family = None
            if name.endswith("_fence"):
                family = "_fence"
            elif name.endswith("_pane") or name == "iron_bars":
                family = "pane"
            elif name.endswith("_wall"):
                family = "_wall"
            if family is None:
                continue
            new = {}
            x, y, z = pos
            for side, (dx, dz) in FACINGS.items():
                n = self.get(x + dx, y, z + dz)
                joins = solid(n) or (family == "pane" and (n.endswith("_pane") or n == "iron_bars")) or (family != "pane" and n.endswith(family))
                new[side] = ("low" if joins else "none") if family == "_wall" else ("true" if joins else "false")
            if family == "_wall":
                new["up"] = "true"
            new["waterlogged"] = "false"
            self.blocks[pos] = (name, new)

    # --- Writing ----------------------------------------------------------------------------------
    def write(self, path):
        for p in self.blocks:
            assert all(0 <= p[i] < self.size[i] for i in range(3)), f"block outside the template {self.size}: {p} in {path}"
        palette, index, entries = [], {}, []
        for pos in sorted(self.blocks, key=lambda p: (p[1], p[2], p[0])):
            name, props = self.blocks[pos]
            if name == "structure_void":
                continue
            key = (name, tuple(sorted(props.items())))
            if key not in index:
                index[key] = len(palette)
                palette.append(key)
            fields = [_tag(9, "pos", _ints(pos)), _tag(3, "state", struct.pack(">i", index[key]))]
            if pos in self.nbt:
                fields.append(_value("nbt", self.nbt[pos]))
            entries.append(_compound(fields))
        palette_entries = []
        for name, props in palette:
            fields = [_tag(8, "id", _str("minecraft:" + name))]
            if props:
                fields.append(_tag(10, "properties", _compound([_tag(8, k, _str(v)) for k, v in props])))
            palette_entries.append(_compound(fields))
        entity_entries = [_compound([_tag(9, "pos", _doubles(pos)), _tag(9, "blockPos", _ints(bp)), _value("nbt", nbt)])
                          for pos, bp, nbt in self.entities]
        root = _compound([
            _tag(9, "size", _ints(self.size)),
            _tag(9, "entities", bytes([10]) + struct.pack(">i", len(entity_entries)) + b"".join(entity_entries)),
            _tag(9, "blocks", bytes([10]) + struct.pack(">i", len(entries)) + b"".join(entries)),
            _tag(9, "palette", bytes([10]) + struct.pack(">i", len(palette_entries)) + b"".join(palette_entries)),
            _tag(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
        ])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with gzip.open(path, "wb") as f:
            f.write(bytes([10]) + _str("") + root)
        return len(entries)


# --- NBT encoding ---------------------------------------------------------------------------------
BYTE_FIELDS = {"PersistenceRequired", "is_waxed", "has_glowing_text", "Slot"}


def _str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _tag(t, name, payload):
    return bytes([t]) + _str(name) + payload


def _ints(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)


def _doubles(values):
    return bytes([6]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">d", v) for v in values)


def _compound(entries):
    return b"".join(entries) + b"\x00"


def _payload(name, v):
    """(tag type, payload) for a python value: dicts are compounds, lists are lists of their first element's type."""
    if isinstance(v, dict):
        return 10, _compound([_value(k, x) for k, x in v.items()])
    if isinstance(v, list):
        if not v:
            return 9, bytes([0]) + struct.pack(">i", 0)
        types = [_payload(name, x) for x in v]
        return 9, bytes([types[0][0]]) + struct.pack(">i", len(v)) + b"".join(p for _, p in types)
    if isinstance(v, bool):
        return 1, struct.pack(">b", 1 if v else 0)
    if isinstance(v, int):
        if name in BYTE_FIELDS:
            return 1, struct.pack(">b", v)
        return 3, struct.pack(">i", v)
    return 8, _str(str(v))


def _value(name, v):
    t, payload = _payload(name, v)
    return _tag(t, name, payload)
