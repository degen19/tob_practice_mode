import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, collections, statistics as st, sys

ROOT = TOP
EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
SPELL = {int(k): v for k, v in EN["spell"].items()}
STALLS = {"SPELLBOOK_SWAP", "HUMIDIFY", "HUNTER_KIT", "NPC_CONTACT"}
clean = json.load(open(f"{ROOT}/clean.json"))


def room(s, uuid):
    ev = json.load(open(f"{ROOT}/{s}/{uuid}.json"))
    downs = [(e["tick"], e["bloatDown"]) for e in ev if e["type"] == 110]
    ups = [e["tick"] for e in ev if e["type"] == 111]
    death = [e["tick"] for e in ev if e["type"] == 9]
    players = sorted({e["player"]["name"] for e in ev if e["type"] == 4})
    atk = collections.defaultdict(collections.Counter)
    stalls = collections.defaultdict(list)
    first_atk = {}
    for e in ev:
        if e["type"] == 5:
            n = e["player"]["name"]
            a = ATK.get(e["attack"]["type"], str(e["attack"]["type"]))
            atk[n][a] += 1
            first_atk.setdefault(n, e["tick"])
        elif e["type"] == 11:
            sp = SPELL.get(e["spell"]["type"], str(e["spell"]["type"]))
            if sp in STALLS:
                stalls[e["player"]["name"]].append((e["tick"], sp))
    return dict(downs=downs, ups=ups, death=death[0] if death else None, players=players,
                atk=atk, stalls=stalls, first=first_atk)


for s in map(str, range(1, 6)):
    rs = [(u, t, room(s, u)) for u, t in clean[s]]
    nd = collections.Counter(len(r["downs"]) for _, _, r in rs)
    walk1 = [r["downs"][0][1].get("walkTime") for _, _, r in rs if r["downs"]]
    d1 = [r["downs"][0][0] for _, _, r in rs if r["downs"]]
    weapons = collections.Counter()
    stall_n = collections.Counter()
    stall_ticks = collections.Counter()
    for _, _, r in rs:
        for n, c in r["atk"].items():
            weapons.update(c)
        for n, l in r["stalls"].items():
            for t, sp in l:
                stall_n[sp] += 1
                # relative to first down
                if r["downs"]:
                    stall_ticks[(sp, t - r["downs"][0][0])] += 1
    print(f"=== scale {s}: {len(rs)} rooms, ticks median {st.median(t for _, t, _ in rs)}")
    print("  downs per room:", dict(nd))
    print("  first down tick:", collections.Counter(d1).most_common(6), " walkTime:", collections.Counter(walk1).most_common(4))
    print("  attacks:", weapons.most_common(14))
    print("  stalls:", dict(stall_n), "rooms with stalls:", sum(1 for _, _, r in rs if r["stalls"]))
    print("  stall tick vs first down:", sorted(stall_ticks.items(), key=lambda x: -x[1])[:12])
