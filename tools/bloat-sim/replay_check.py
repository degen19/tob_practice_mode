"""Damage check: replay each clean Blert room's real attacks (and its real down ticks) through the sim's hit models
and the room's half/full rule, and compare the HP at the first down and the kill tick with Blert's."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, random, statistics, sys, collections
sys.path.insert(0, HERE)
from bloat import hitpoints
from combat import Weapons

ROOT = TOP
EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
clean = json.load(open(f"{ROOT}/clean.json"))


def replay(ev, team, w, rng):
    downs = [e["tick"] for e in ev if e["type"] == 110]
    attacks = collections.defaultdict(list)
    for e in ev:
        if e["type"] == 5:
            attacks[e["tick"]].append(ATK.get(e["attack"]["type"]))
    hp = mx = hitpoints(team)
    defence = 100
    landing = collections.defaultdict(list)
    hp_down = None
    dead = None
    real = next(e['tick'] for e in ev if e['type'] == 9)
    for t in range(0, real + 1):
        down_at = max([d for d in downs if d <= t], default=None)
        full = down_at is not None and 0 <= 32 - (t - down_at) <= 31
        if down_at is not None and t - down_at == 29:
            defence = 100
        if t in downs and hp_down is None:
            hp_down = hp
        for dmg, wpn in landing.pop(t, []):
            dealt = dmg if full else dmg // 2
            hp -= dealt
            if wpn == "BGS_SPEC":
                defence = max(0, defence - dealt)
            if hp <= 0 and dead is None:
                dead = t
        for wpn in attacks.get(t, []):
            for delay, dmg in w.hits(wpn, defence, rng):
                landing[t + delay].append((dmg, wpn))
    return dead, hp_down, hp


def check(w, seeds=20, verbose=True):
    err = []
    for s in clean:
        team = int(s)
        dk, dh = [], []
        for u, _ in clean[s]:
            ev = json.load(open(f"{ROOT}/{s}/{u}.json"))
            real_death = next(e["tick"] for e in ev if e["type"] == 9)
            hp = {e["tick"]: e["npc"]["hitpoints"] >> 16 for e in ev if e["type"] in (7, 8, 9)}
            d0 = next(e["tick"] for e in ev if e["type"] == 110)
            for k in range(seeds):
                death, hpd, left = replay(ev, team, w, random.Random(k))
                dk.append(left)
                dh.append((hpd or 0) - hp.get(d0, 0))
        err.append((s, statistics.mean(dk), statistics.pstdev(dk), statistics.mean(dh)))
        if verbose:
            print(f"scale {s}: sim HP left at Blert's kill tick: mean {statistics.mean(dk):+.0f} (sd {statistics.pstdev(dk):.0f}), dead by then {sum(1 for x in dk if x <= 0) / len(dk):.0%}; "
                  f"HP at first down sim - Blert {statistics.mean(dh):+.0f}")
    return err


if __name__ == "__main__":
    check(Weapons())
