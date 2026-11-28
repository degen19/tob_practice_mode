import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, collections, statistics as st

ROOT = TOP
EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
SPELL = {int(k): v for k, v in EN["spell"].items()}
clean = json.load(open(f"{ROOT}/clean.json"))
HP = {"1": 1500, "2": 1500, "3": 1500, "4": 1750, "5": 2000}

for s in map(str, range(1, 6)):
    after = []
    hp_at_down = []
    rel = collections.Counter()
    pre_atk = []
    pos_at_down = collections.Counter()
    for u, t in clean[s]:
        ev = json.load(open(f"{ROOT}/{s}/{u}.json"))
        downs = [e["tick"] for e in ev if e["type"] == 110]
        death = next(e["tick"] for e in ev if e["type"] == 9)
        d = downs[-1]
        after.append(death - d)
        npc = {e["tick"]: e["npc"]["hitpoints"] >> 16 for e in ev if e["type"] in (7, 8, 9) and e["npc"]["id"] in range(8359, 8360)}
        hp_at_down.append(npc.get(downs[0], None))
        pre_atk.append(sum(1 for e in ev if e["type"] == 5 and e["tick"] < downs[0]))
        for e in ev:
            if e["type"] == 5 and e["tick"] >= downs[0]:
                k = 0
                while k + 1 < len(downs) and e["tick"] >= downs[k + 1]:
                    k += 1
                rel[(k, e["tick"] - downs[k], ATK.get(e["attack"]["type"]))] += 1
            if e["type"] == 4 and e["tick"] == downs[0]:
                pos_at_down[(e["xCoord"] - 3280, e["yCoord"] - 4432)] += 1
    print(f"=== scale {s}")
    print("  death - last down:", collections.Counter(after).most_common(8))
    print("  HP at first down:", collections.Counter(hp_at_down).most_common(5), "of", HP[s])
    print("  attacks before first down per room:", collections.Counter(pre_atk).most_common(6))
    by = collections.defaultdict(collections.Counter)
    for (k, dt, a), n in rel.items():
        by[k][(dt, a)] += n
    for k in sorted(by):
        print(f"  down {k + 1} attack ticks (dt, weapon):", sorted(by[k].items(), key=lambda x: (x[0][0], -x[1]))[:40])
    print("  player tiles at down (room-local):", pos_at_down.most_common(8))
