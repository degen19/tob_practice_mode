"""Sim vs the Blert room a procedure came from, from the same start: each player's tile per tick, side by side, and
the mean distance (tiles) between them over the walk.

python3 compare_blert.py <procedure.json> [ticks]
"""
import json
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
sys.path.insert(0, HERE)
import team

BX, BY = 3280, 4432


def blert_tracks(uuid, scale):
    ev = json.load(open(os.path.join(TOP, str(scale), uuid + ".json")))
    pos, b = {}, {}
    for e in ev:
        if e["type"] == 4:
            pos.setdefault(e["player"]["name"], {})[e["tick"]] = (e["xCoord"] - BX, e["yCoord"] - BY)
        elif e["type"] in (7, 8):
            b[e["tick"]] = (e["xCoord"] - BX, e["yCoord"] - BY)
    return pos, b


def same_start_seed(proc, b, tries=400):
    for s in range(tries):
        f = team.run(proc, s, record=True)["frames"]
        if tuple(f[0]["b"][:2]) == b[0] and tuple(f[1]["b"][:2]) == b[1]:
            return s, f
    return None, None


def main(path, ticks=40):
    proc = json.load(open(path))
    scale = len(proc["players"])
    pos, b = blert_tracks(proc["source"], scale)
    seed, frames = same_start_seed(proc, b)
    if seed is None:
        print("no sim seed with Blert's start")
        return
    names = [p["name"] for p in proc["players"]]
    total, n = 0, 0
    print(f"seed {seed}; each cell: sim tile / Blert tile")
    for t in range(min(ticks, len(frames))):
        cells = []
        for i, nm in enumerate(names):
            s = frames[t]["p"][i]
            r = pos.get(nm, {}).get(t)
            mark = "SCY" if any(a[0] == i for a in frames[t].get("a", [])) else ""
            cells.append(f"{tuple(s) if s else '-'}/{r if r else '-'}{mark}")
            if s and r and 8 <= r[0] <= 23:
                total += max(abs(s[0] - r[0]), abs(s[1] - r[1]))
                n += 1
        print(f"t{t:2d} B{tuple(frames[t]['b'][:2])}/{b.get(t)}  " + "  ".join(cells))
    print(f"mean tile distance sim vs Blert over ticks 0-{ticks - 1}: {total / max(n, 1):.2f}")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 40)
