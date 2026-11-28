"""Sim vs Blert on rooms whose first down came at walk tick 39: walk swings, HP at the down, down to kill."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, sys, statistics, collections
sys.path.insert(0, HERE)
import team
import replay_check as rc

FILES = {3: "3s", 4: "4s", 5: "5s", 2: "2s", 1: "1s"}


def blert(scale):
    out = []
    for u, _ in rc.clean[str(scale)]:
        ev = json.load(open(f"{rc.ROOT}/{scale}/{u}.json"))
        downs = [e["tick"] for e in ev if e["type"] == 110]
        d = downs[0]
        hp = {e["tick"]: e["npc"]["hitpoints"] >> 16 for e in ev if e["type"] in (7, 8, 9)}
        death = next(e["tick"] for e in ev if e["type"] == 9)
        walk = collections.Counter(rc.ATK[e["attack"]["type"]] for e in ev if e["type"] == 5 and e["tick"] < d)
        out.append(dict(d=d, hp=hp[d], after=death - downs[-1], total=death, walk=sum(walk.values()), downs=len(downs)))
    return out


def sim(scale, n=300):
    proc = json.load(open(f"{HERE}/procedures/{FILES[scale]}.json"))
    out = []
    for s in range(n):
        r = team.run(proc, s, record=True)
        if not r["ticks"] or r["deaths"]:
            continue
        fr = r["frames"]
        d = next(i for i, f in enumerate(fr) if f.get("down"))
        downs = [i for i, f in enumerate(fr) if f.get("down")]
        walk = sum(len(f.get("a", [])) for f in fr[:d])
        out.append(dict(d=d, hp=fr[d]["b"][2], after=r["ticks"] - downs[-1], total=r["ticks"], walk=walk, downs=len(downs)))
    return out


def show(scale):
    for name, rows in (("Blert", blert(scale)), ("Sim", sim(scale))):
        r39 = [r for r in rows if r["d"] == 39] or rows
        m = lambda k: round(statistics.mean(r[k] for r in r39), 1)
        print(f"  {name:5s} n={len(r39):3d} at first down 39: walk attacks {m('walk')}, HP at down {m('hp')}, "
              f"last down->kill {m('after')}, total {m('total')} (median {statistics.median(r['total'] for r in r39)}), downs {m('downs')}")


if __name__ == "__main__":
    for s in map(int, sys.argv[1:] or ["3", "4", "5"]):
        print("scale", s)
        show(s)
