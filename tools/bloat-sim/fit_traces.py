"""Fit weapon maxes so replaying Blert's own attacks through the sim reproduces Blert's HP trace, tick by tick."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, random, collections, sys
sys.path.insert(0, HERE)
from bloat import hitpoints
from combat import Weapons

ROOT = TOP
EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
clean = json.load(open(f"{ROOT}/clean.json"))

ROOMS = []
for s in clean:
    for u, _ in clean[s]:
        ev = json.load(open(f"{ROOT}/{s}/{u}.json"))
        downs = [e["tick"] for e in ev if e["type"] == 110]
        hp = {e["tick"]: e["npc"]["hitpoints"] >> 16 for e in ev if e["type"] in (7, 8, 9)}
        death = next(e["tick"] for e in ev if e["type"] == 9)
        atk = collections.defaultdict(list)
        for e in ev:
            if e["type"] == 5:
                atk[e["tick"]].append(ATK.get(e["attack"]["type"]))
        ROOMS.append((int(s), downs, hp, death, atk))


def trace(room, w, rng):
    team, downs, real, death, atk = room
    hp = hitpoints(team)
    defence = 100
    landing = collections.defaultdict(list)
    out = {}
    for t in range(0, death + 1):
        d = max([x for x in downs if x <= t], default=None)
        full = d is not None and 1 <= t - d <= 32
        if d is not None and t - d == 29:
            defence = 100
        for dmg, wpn in landing.pop(t, []):
            dealt = dmg if full else dmg // 2
            hp -= dealt
            if wpn == "BGS_SPEC":
                defence = max(0, defence - dealt)
        out[t] = max(hp, 0)
        for wpn in atk.get(t, []):
            for delay, dmg in w.hits(wpn, defence, rng):
                landing[t + delay].append((dmg, wpn))
    return out


def loss(params, seeds=12, scales=None, by_scale=False):
    w = Weapons(**params)
    per = collections.defaultdict(lambda: [0.0, 0])
    for i, room in enumerate(ROOMS):
        if scales and room[0] not in scales:
            continue
        real = room[2]
        sim = collections.defaultdict(float)
        for k in range(seeds):
            for t, v in trace(room, w, random.Random(i * 100 + k)).items():
                sim[t] += v / seeds
        for t, v in sim.items():
            if t in real and t < room[3]:
                per[room[0]][0] += (v - real[t]) ** 2
                per[room[0]][1] += 1
    if by_scale:
        return {s: (a / n) ** 0.5 for s, (a, n) in sorted(per.items())}
    return sum(a for a, _ in per.values()) / sum(n for _, n in per.values())


if __name__ == "__main__":
    params = dict(scythe_max=63, claw_max=50, bgs_max=90, chally_max=70)
    steps = dict(scythe_max=4, claw_max=4, bgs_max=8, chally_max=8)
    best = loss(params)
    print("start rmse", best ** 0.5, loss(params, by_scale=True))
    for rnd in range(4):
        for k in params:
            for d in (steps[k], -steps[k]):
                while True:
                    trial = dict(params, **{k: params[k] + d})
                    if trial[k] < 1:
                        break
                    l = loss(trial)
                    if l < best:
                        best, params = l, trial
                    else:
                        break
        steps = {k: max(1, v // 2) for k, v in steps.items()}
        print("round", rnd, params, "rmse", round(best ** 0.5, 1))
    print("by scale rmse", {k: round(v, 1) for k, v in loss(params, by_scale=True).items()})
    json.dump(params, open(os.path.join(HERE, "weapons_fit.json"), "w"))
