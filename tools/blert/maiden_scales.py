"""Maiden by scale from Blert's fastest rooms (maiden_fetch.sh): room length, spawn ticks, leaks, who freezes and when.

    python3 -I maiden_scales.py <dir with maiden/<scale>/> [scale ...]
"""
import collections
import glob
import json
import statistics
import sys

DEFS = json.load(open(__file__.rsplit("/", 1)[0] + "/defs/attack_definitions.json"))
NAME = {d["protoId"]: d["name"] for d in DEFS}
CRAB = 8366
SHORT = {"TWISTED_BOW": "tbow", "ASCENSION_CROSSBOWS": "asc", "ZCB_SPEC": "zcbS", "ZCB_AUTO": "zcb", "SCYTHE": "scy",
         "DINHS_SPEC": "dinh", "BLOWPIPE": "bp", "DRAGON_CLAWS_SPEC": "claw"}


def short(t):
    n = NAME.get(t, str(t))
    return "barr" if "BARRAGE" in n else "rush" if "RUSH" in n else SHORT.get(n, n.lower())


def room(ev):
    end = max(e["tick"] for e in ev)
    births = {}
    for e in ev:
        if e["type"] in (7, 8) and e["npc"]["id"] == CRAB and e["npc"]["roomId"] not in births:
            births[e["npc"]["roomId"]] = e["tick"]
    ticks = sorted(set(births.values()))
    waves = [ticks[0]] if ticks else []
    for t in ticks[1:]:
        if t - waves[-1] > 5:
            waves.append(t)
    atk = [e for e in ev if e["type"] == 5]
    who = collections.Counter(e["player"]["name"] for e in atk if short(e["attack"]["type"]) in ("barr", "rush"))
    freezers = [n for n, c in who.items() if c >= 3]
    first = {}
    for k, w in enumerate(waves):
        for n in freezers:
            fz = sorted(e["tick"] - w for e in atk if e["player"]["name"] == n and short(e["attack"]["type"]) in ("barr", "rush")
                        and w - 2 <= e["tick"] <= w + 20 and births.get(e["attack"]["target"].get("roomId")) is not None)
            first.setdefault(k, []).append(fz)
    weapons = collections.Counter(short(e["attack"]["type"]) for e in atk)
    return {"end": end, "waves": waves, "leaks": sum(1 for e in ev if e["type"] == 100), "players": len({e["player"]["name"] for e in ev if e["type"] == 4}),
            "freezers": len(freezers), "first": first, "weapons": weapons}


def main():
    base, scales = sys.argv[1], sys.argv[2:] or ["2", "3", "4", "5"]
    for s in scales:
        rooms = []
        for f in sorted(glob.glob(f"{base}/maiden/{s}/*-*.json")):
            try:
                ev = json.load(open(f))
            except ValueError:
                continue
            if isinstance(ev, list) and ev:
                rooms.append(room(ev))
        if not rooms:
            continue
        ends = [r["end"] for r in rooms]
        print(f"== {s}s: {len(rooms)} rooms, room ticks median {statistics.median(ends)} (best {min(ends)}, worst {max(ends)})")
        for k, name in enumerate(["70s", "50s", "30s"]):
            w = [r["waves"][k] for r in rooms if len(r["waves"]) > k]
            if w:
                print(f"   {name} spawn tick median {statistics.median(w)} (range {min(w)}-{max(w)})")
        print(f"   leaks/room {statistics.mean(r['leaks'] for r in rooms):.2f}; freezers per room {collections.Counter(r['freezers'] for r in rooms).most_common()}")
        for k, name in enumerate(["70s", "50s", "30s"]):
            seqs = collections.Counter()
            for r in rooms:
                for fz in r["first"].get(k, []):
                    seqs[" ".join(f"{x:+d}" for x in fz[:5])] += 1
            print(f"   {name} freeze ticks (per freezer, from spawn): " + "; ".join(f"{v}x [{q}]" for q, v in seqs.most_common(5)))
        tot = collections.Counter()
        for r in rooms:
            tot.update(r["weapons"])
        print("   attacks/room: " + ", ".join(f"{n} {c / len(rooms):.1f}" for n, c in tot.most_common(10)))


if __name__ == "__main__":
    main()
