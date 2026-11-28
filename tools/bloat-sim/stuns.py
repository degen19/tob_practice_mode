import json, sys, os, collections

ROOT = sys.argv[1] if len(sys.argv) > 1 else TOP


def load(s, uuid):
    return json.load(open(f"{ROOT}/{s}/{uuid}.json"))


def analyse(ev):
    pos = collections.defaultdict(dict)
    for e in ev:
        if e["type"] == 4:
            pos[e["tick"]][e["player"]["name"]] = (e["xCoord"], e["yCoord"])
    hits = []
    for e in ev:
        if e["type"] != 113:
            continue
        t = e["tick"]
        tiles = {(h["x"], h["y"]) for h in e["bloatHands"]}
        for name, p in pos.get(t - 1, {}).items():
            if p in tiles:
                frozen = sum(1 for k in range(0, 8) if pos.get(t + k, {}).get(name) == p)
                atk = [x["tick"] - t for x in ev if x["type"] == 5 and x["player"]["name"] == name and t <= x["tick"] < t + 8]
                hits.append((t, name, p, frozen, atk))
    return hits


summary = {}
for s in range(1, 6):
    idx = json.load(open(f"{ROOT}/idx_{s}.json"))
    keep = []
    for c in idx:
        ev = load(s, c["uuid"])
        h = analyse(ev)
        ticks = c["splits"]["27"]["ticks"]
        if not h:
            keep.append((c["uuid"], ticks))
        else:
            print(f"scale {s} {ticks}t {c['uuid'][:8]} stunned: {h}")
    summary[s] = keep
    print(f"scale {s}: {len(keep)}/{len(idx)} clean, ticks {[t for _, t in keep]}")
json.dump(summary, open(f"{ROOT}/clean.json", "w"), indent=1)
