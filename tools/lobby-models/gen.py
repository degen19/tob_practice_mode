"""Builds the ToB lobby's floor, rim, centre symbol and pedestal models (OSRS's original model format).

Run from the repo root: python3 tools/lobby-models/gen.py
Writes the models (pack/models/tob_lobby_*.dat), a loc for each (pack/configs/tob_lobby_floor.toml), the enum of rim tiles
(enum.tob_lobby_edges: lobby tile x * 64 + z -> its loc), and their ids at the end of the module's gamevals.toml.
Units: 128 to a tile; +x east, +z north, -y up. A tile's model is centred on it.
"""
import math
import os
import struct

OUT = "content/raids/tob-bloat/pack/src/main/resources/pack/models"
CONFIGS = "content/raids/tob-bloat/pack/src/main/resources/pack/configs/tob_lobby_floor.toml"
GAMEVALS = "content/raids/tob-bloat/src/main/resources/gamevals.toml"
GAMEVALS_MARK = "# The lobby's floor: written by tools/lobby-models/gen.py, everything below this line."
MODEL_BASE = 64000
LOC_BASE = 64000
EXTRA_LOCS = {"tob_lobby_gear_rack": 64199}
TILE = 128
HALF = TILE // 2
SIZE = 40
CENTRE = SIZE / 2 + 0.5
RADIUS = 16.5
BANDS = 6
LIFT = 3
EDGE_BAND = 0.75


def hsl(h, s, l):
    return (h << 10) | (s << 7) | l


VOID = hsl(0, 0, 1)
GRID = hsl(62, 5, 5)
FLOOR = [hsl(62, 5, l) for l in (16, 14, 12, 11, 10, 9)]
RIM = hsl(60, 4, 22)
GOLD = hsl(7, 6, 66)
GOLD_DARK = hsl(7, 6, 46)
STONE = hsl(60, 2, 18)


def smart(v):
    if -64 <= v < 64:
        return struct.pack(">B", v + 64)
    return struct.pack(">H", v + 0xC000)


class Model:
    def __init__(self):
        self.verts = []
        self.faces = []

    def vert(self, x, y, z):
        v = (int(round(x)), int(round(y)), int(round(z)))
        if v in self.verts:
            return self.verts.index(v)
        self.verts.append(v)
        return len(self.verts) - 1

    def tri(self, a, b, c, colour):
        if len({a, b, c}) == 3:
            self.faces.append((a, b, c, colour))

    def poly(self, points, y, colour):
        """A convex polygon (x, z) at height y, drawn from both sides."""
        ids = [self.vert(x, y, z) for x, z in points]
        for i in range(1, len(ids) - 1):
            self.tri(ids[0], ids[i + 1], ids[i], colour)
            self.tri(ids[0], ids[i], ids[i + 1], colour)

    def quad(self, a, b, c, d, y, colour):
        self.poly([a, b, c, d], y, colour)

    def encode(self):
        flags, xs, ys, zs = bytearray(), bytearray(), bytearray(), bytearray()
        px = py = pz = 0
        for x, y, z in self.verts:
            f = 0
            if x != px:
                f |= 1
                xs += smart(x - px)
            if y != py:
                f |= 2
                ys += smart(y - py)
            if z != pz:
                f |= 4
                zs += smart(z - pz)
            flags.append(f)
            px, py, pz = x, y, z
        types, idx, colours = bytearray(), bytearray(), bytearray()
        last = 0
        for a, b, c, colour in self.faces:
            types.append(1)
            idx += smart(a - last) + smart(b - a) + smart(c - b)
            last = c
            colours += struct.pack(">H", colour)
        body = flags + types + idx + colours + xs + ys + zs
        footer = struct.pack(">HHBBBBBBHHHH", len(self.verts), len(self.faces), 0, 0, 0, 0, 0, 0, len(xs), len(ys), len(zs), len(idx))
        return bytes(body + footer)


def save(name, model):
    with open(os.path.join(OUT, name + ".dat"), "wb") as f:
        f.write(model.encode())


def square(x0, z0, x1, z1):
    return [(x0, z0), (x1, z0), (x1, z1), (x0, z1)]


def floor_tile(colour):
    m = Model()
    inner = HALF - 6
    m.poly(square(-inner, -inner, inner, inner), -LIFT, colour)
    for a, b in [((-HALF, -HALF), (HALF, -inner)), ((-HALF, inner), (HALF, HALF)), ((-HALF, -inner), (-inner, inner)), ((inner, -inner), (HALF, inner))]:
        m.poly(square(a[0], a[1], b[0], b[1]), -LIFT, GRID)
    return m


def void_tile():
    m = Model()
    m.poly(square(-HALF, -HALF, HALF, HALF), -LIFT, VOID)
    return m


def clip(points, cx, cz, r):
    """The convex polygon [points] cut to the disc of radius r round (cx, cz), arcs drawn every few degrees."""
    out = []
    n = len(points)
    for i in range(n):
        p, q = points[i], points[(i + 1) % n]
        pin = math.hypot(p[0] - cx, p[1] - cz) <= r
        if pin:
            out.append(p)
        dx, dz = q[0] - p[0], q[1] - p[1]
        fx, fz = p[0] - cx, p[1] - cz
        a = dx * dx + dz * dz
        b = 2 * (fx * dx + fz * dz)
        c = fx * fx + fz * fz - r * r
        disc = b * b - 4 * a * c
        if disc > 0:
            for t in sorted([(-b - math.sqrt(disc)) / (2 * a), (-b + math.sqrt(disc)) / (2 * a)]):
                if 0 < t < 1:
                    out.append((p[0] + t * dx, p[1] + t * dz))
    if len(out) < 3:
        return []
    mx = sum(p[0] for p in out) / len(out)
    mz = sum(p[1] for p in out) / len(out)
    out.sort(key=lambda v: math.atan2(v[1] - mz, v[0] - mx))
    arc = []
    for i in range(len(out)):
        p, q = out[i], out[(i + 1) % len(out)]
        arc.append(p)
        on_p = abs(math.hypot(p[0] - cx, p[1] - cz) - r) < 0.5
        on_q = abs(math.hypot(q[0] - cx, q[1] - cz) - r) < 0.5
        if on_p and on_q:
            a0 = math.atan2(p[1] - cz, p[0] - cx)
            a1 = math.atan2(q[1] - cz, q[0] - cx)
            while a1 - a0 > math.pi:
                a1 -= 2 * math.pi
            while a0 - a1 > math.pi:
                a1 += 2 * math.pi
            steps = max(1, int(abs(a1 - a0) / math.radians(6)))
            for s in range(1, steps):
                a = a0 + (a1 - a0) * s / steps
                arc.append((cx + r * math.cos(a), cz + r * math.sin(a)))
    return arc


def edge_tile(tx, tz):
    """A rim tile: black where it's off the disc, the floor's outer band and a lighter rim where it's on."""
    cx = (CENTRE - (tx + 0.5)) * TILE
    cz = (CENTRE - (tz + 0.5)) * TILE
    m = Model()
    m.poly(square(-HALF, -HALF, HALF, HALF), -LIFT, VOID)
    tile = square(-HALF, -HALF, HALF, HALF)
    rim = clip(tile, cx, cz, RADIUS * TILE)
    if rim:
        m.poly(rim, -LIFT - 2, RIM)
    inner = clip(tile, cx, cz, (RADIUS - 0.35) * TILE)
    if inner:
        m.poly(inner, -LIFT - 4, FLOOR[-1])
    return m


def ring(m, r0, r1, y, colour, steps=96, a0=0.0, a1=2 * math.pi):
    for i in range(steps):
        t0 = a0 + (a1 - a0) * i / steps
        t1 = a0 + (a1 - a0) * (i + 1) / steps
        pts = [(r0 * math.cos(t0), r0 * math.sin(t0)), (r1 * math.cos(t0), r1 * math.sin(t0)), (r1 * math.cos(t1), r1 * math.sin(t1)), (r0 * math.cos(t1), r0 * math.sin(t1))]
        m.poly(pts, y, colour)


def symbol():
    """The gold circle on the floor round Mr. Mordaut: two rings, ticks between them, and an eight-pointed star."""
    m = Model()
    y = -LIFT - 6
    t = TILE
    ring(m, 5.55 * t, 5.8 * t, y, GOLD)
    ring(m, 4.55 * t, 4.7 * t, y, GOLD)
    for i in range(48):
        a = 2 * math.pi * i / 48
        long_tick = i % 4 == 0
        r0, r1 = (4.7 * t, 5.55 * t) if long_tick else (5.05 * t, 5.55 * t)
        w = 0.035 if long_tick else 0.02
        pts = [(r0 * math.cos(a - w * 5.5 / 4.7), r0 * math.sin(a - w * 5.5 / 4.7)), (r1 * math.cos(a - w), r1 * math.sin(a - w)), (r1 * math.cos(a + w), r1 * math.sin(a + w)), (r0 * math.cos(a + w * 5.5 / 4.7), r0 * math.sin(a + w * 5.5 / 4.7))]
        m.poly(pts, y, GOLD if long_tick else GOLD_DARK)
    for i in range(8):
        a = 2 * math.pi * i / 8
        tip = (4.4 * t * math.cos(a), 4.4 * t * math.sin(a))
        left = (2.3 * t * math.cos(a - math.pi / 8), 2.3 * t * math.sin(a - math.pi / 8))
        right = (2.3 * t * math.cos(a + math.pi / 8), 2.3 * t * math.sin(a + math.pi / 8))
        m.poly([left, tip, right], y, GOLD)
        inner = (2.0 * t * math.cos(a), 2.0 * t * math.sin(a))
        m.poly([left, inner, right], y, GOLD_DARK)
    ring(m, 2.25 * t, 2.4 * t, y, GOLD)
    return m


def pedestal(size):
    """A boss's spot: a dark stone disc a little wider than it, gold round the edge."""
    m = Model()
    r = (size / 2 + 0.5) * TILE
    ring(m, 0, r - 12, -LIFT - 6, STONE, steps=48)
    ring(m, r - 12, r, -LIFT - 7, GOLD, steps=48)
    return m


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.startswith("tob_lobby_"):
            os.remove(os.path.join(OUT, f))
    for i, colour in enumerate(FLOOR):
        save(f"tob_lobby_floor_{i}", floor_tile(colour))
    save("tob_lobby_void", void_tile())
    save("tob_lobby_symbol", symbol())
    for size in (3, 4, 5, 6, 7):
        save(f"tob_lobby_pedestal_{size}", pedestal(size))
    edges = []
    for tx in range(SIZE):
        for tz in range(SIZE):
            near = math.hypot(tx + 0.5 - CENTRE, tz + 0.5 - CENTRE)
            if abs(near - RADIUS) <= EDGE_BAND:
                save(f"tob_lobby_edge_{tx}_{tz}", edge_tile(tx, tz))
                edges.append((tx, tz))
    floors = [f"tob_lobby_floor_{i}" for i in range(len(FLOOR))] + ["tob_lobby_void"]
    scenery = ["tob_lobby_symbol"] + [f"tob_lobby_pedestal_{size}" for size in (3, 4, 5, 6, 7)]
    tiles = floors + [f"tob_lobby_edge_{x}_{z}" for x, z in edges]
    names = tiles + scenery
    with open(CONFIGS, "w") as f:
        f.write("# The lobby's floor, rim, centre symbol and pedestals: written by tools/lobby-models/gen.py.\n")
        for name in tiles:
            f.write(f'\n[[object]]\nid = "loc.{name}"\nobjectModels = ["models.{name}"]\nobjectTypes = [22]\ninteractive = 0\nblockWalk = 0\nblockRange = false\n')
        for name in scenery:
            f.write(f'\n[[object]]\nid = "loc.{name}"\nobjectModels = ["models.{name}"]\ninteractive = 0\nblockWalk = 0\nblockRange = false\n')
        f.write('\n[[enum]]\nisServerOnly = true\nid = "enum.tob_lobby_edges"\nkeyType = "INT"\nvalueType = "LOC"\n\n[enum.values]\n')
        for x, z in edges:
            f.write(f'{x * 64 + z} = "loc.tob_lobby_edge_{x}_{z}"\n')
    with open(GAMEVALS) as f:
        text = f.read()
    if GAMEVALS_MARK in text:
        text = text[: text.index(GAMEVALS_MARK)]
    text = text.rstrip("\n") + "\n\n" + GAMEVALS_MARK + "\n[gamevals.models]\n"
    text += "".join(f"{n} = {MODEL_BASE + i}\n" for i, n in enumerate(names))
    text += "\n[gamevals.loc]\n" + "".join(f"{n} = {LOC_BASE + i}\n" for i, n in enumerate(names))
    text += "".join(f"{n} = {i}\n" for n, i in EXTRA_LOCS.items())
    with open(GAMEVALS, "w") as f:
        f.write(text)
    print(len(edges), "edge tiles,", len(names), "models and locs")


if __name__ == "__main__":
    main()
