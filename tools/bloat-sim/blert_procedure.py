"""A procedure (procedures/*.json) read off one Blert room: each player's gate and entry tick, walk swing ticks and
which side of him (front/back/any, by his heading, so it mirrors), stalls, walk specs and down specs.

python3 blert_procedure.py <scale> [uuid]   uuid defaults to the scale's fastest clean room; prints the JSON
"""
import json
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
sys.path.insert(0, HERE)
from bloat import LOOP

EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
SPELL = {int(k): v for k, v in EN["spell"].items()}
STALLS = {"HUMIDIFY", "SPELLBOOK_SWAP", "NPC_CONTACT", "HUNTER_KIT"}
SPECS = {"BGS_SPEC", "CLAW_SPEC", "CHALLY_SPEC", "ZCB_SPEC"}
BX, BY = 3280, 4432


def side_of(px, py, bx, by, hx, hy):
    """front/back/any: where a swing tile sits along his heading (the 5x5's ends), else any."""
    along = (px - bx - 2) * hx + (py - by - 2) * hy
    if along >= 3:
        return "front"
    if along <= -3:
        return "back"
    return "any"


def build(scale, uuid=None):
    clean = json.load(open(os.path.join(TOP, "clean.json")))
    rooms = sorted(clean[str(scale)], key=lambda r: r[1])
    uuid = uuid or rooms[0][0]
    ev = json.load(open(os.path.join(TOP, str(scale), uuid + ".json")))
    b = {e["tick"]: (e["xCoord"] - BX, e["yCoord"] - BY) for e in ev if e["type"] in (7, 8)}
    downs = [e["tick"] for e in ev if e["type"] == 110]
    pos, swings, specs, stalls = {}, {}, {}, {}
    for e in ev:
        n = e.get("player", {}).get("name")
        if not n:
            continue
        if e["type"] == 4:
            pos.setdefault(n, {})[e["tick"]] = (e["xCoord"] - BX, e["yCoord"] - BY)
        elif e["type"] == 5:
            w = ATK.get(e["attack"]["type"])
            (swings if w == "SCYTHE" else specs).setdefault(n, []).append((e["tick"], w))
        elif e["type"] == 11:
            stalls.setdefault(n, []).append(e["tick"])
    k0 = LOOP.index(b[0])
    k1 = LOOP.index(b[1])
    step = 1 if (k1 - k0) % len(LOOP) in (1, 2) else -1
    east = (b[0][1] == 8 and step == 1) or (b[0][1] == 19 and step == -1)

    def heading(t):
        a, c = b.get(t), b.get(t + 1)
        if not a or not c or a == c:
            return (0, 0)
        return (max(-1, min(1, c[0] - a[0])), max(-1, min(1, c[1] - a[1])))

    def down_of(t):
        ds = [d for d in downs if d <= t and t - d < 33]
        return (downs.index(ds[-1]) + 1, t - ds[-1]) if ds else None

    order = sorted(pos, key=lambda n: min(t for t, p in pos[n].items() if 8 <= p[0] <= 23) if any(8 <= p[0] <= 23 for p in pos[n].values()) else 999)
    players = []
    for n in order:
        ps = pos[n]
        first_in = min(t for t, p in ps.items() if 8 <= p[0] <= 23)
        x0 = ps[min(ps)][0]
        gate = "west" if x0 <= 7 or (x0 < 16 and first_in == 0) else "east"
        walk = [(t, w) for t, w in swings.get(n, []) if down_of(t) is None]
        walk_ticks = [t for t, _ in walk if t < downs[0]] if downs else [t for t, _ in walk]
        sides = []
        for t in walk_ticks:
            p, bb = ps.get(t), b.get(t)
            hx, hy = heading(t)
            sides.append(side_of(p[0], p[1], bb[0], bb[1], hx, hy) if p and bb else "any")
        wspecs = [{"weapon": w, "tick": t} for t, w in specs.get(n, []) if down_of(t) is None and (not downs or t < downs[0])]
        dspecs = []
        for t, w in specs.get(n, []):
            d = down_of(t)
            if d:
                dspecs.append({"weapon": w, "at": d[1], "down": d[0]})
        starts = []
        for i, d in enumerate(downs):
            ts = [t - d for t, _ in swings.get(n, []) + specs.get(n, []) if 0 <= t - d < 33]
            starts.append(min(ts) if ts else 0)
        cast = set(stalls.get(n, []))
        walk_stalls = [t for t in walk_ticks if t + 1 in cast]
        stall = len(walk_stalls) >= 2
        rewalk = any(downs[0] + 33 <= t < (downs[1] if len(downs) > 1 else 10 ** 6) for t, _ in swings.get(n, [])) if downs else True
        players.append({
            "name": n, "gate": gate, "enter": first_in, "stall": stall, "necks": 8 if not stall else 6, "hp": 115,
            "first_swing": min(walk_ticks + [t for t in (s["tick"] for s in wspecs)] or [999]),
            "walk_swings": walk_ticks, "walk_sides": sides, "walk_stalls": walk_stalls, "walk_specs": wspecs,
            "down_specs": dspecs, "down_start": starts or [0], "rewalk_attack": rewalk,
        })
    last = max(b)
    tracks = {n: {str(t): list(p) for t, p in ps.items() if t <= last} for n, ps in pos.items()}
    for pl in players:
        pl["track"] = tracks[pl["name"]]
    return {"name": f"{scale}s from Blert {uuid[:8]}", "source": uuid,
            "bloat_track": {str(t): list(p) for t, p in b.items()}, "downs": downs,
            "entry": {"x": b[0][0], "heading": "east" if east else "west"}, "damage_factor": 1.0, "players": players}


if __name__ == "__main__":
    print(json.dumps(build(int(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else None), indent=1))
