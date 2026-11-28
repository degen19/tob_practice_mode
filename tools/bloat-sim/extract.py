"""Compact replay data for the viewer: one fastest and one median clean room per scale."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, statistics

ROOT = TOP
EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
SPELL = {int(k): v for k, v in EN["spell"].items()}
BX, BY = 3280, 4432
clean = json.load(open(f"{ROOT}/clean.json"))
idx = {s: {c["uuid"]: c for c in json.load(open(f"{ROOT}/idx_{s}.json"))} for s in map(str, range(1, 6))}


def compact(s, uuid, label):
    ev = json.load(open(f"{ROOT}/{s}/{uuid}.json"))
    names = sorted({e["player"]["name"] for e in ev if e["type"] == 4})
    pi = {n: i for i, n in enumerate(names)}
    end = max(e["tick"] for e in ev)
    ticks = [{"p": [None] * len(names), "a": [], "sp": [], "hd": [], "hs": []} for _ in range(end + 1)]
    for e in ev:
        t = ticks[e["tick"]]
        ty = e["type"]
        if ty == 4:
            t["p"][pi[e["player"]["name"]]] = [e["xCoord"] - BX, e["yCoord"] - BY]
        elif ty == 5:
            t["a"].append([pi[e["player"]["name"]], ATK.get(e["attack"]["type"], "?")])
        elif ty == 11:
            t["sp"].append([pi[e["player"]["name"]], SPELL.get(e["spell"]["type"], "?")])
        elif ty in (7, 8, 9):
            t["b"] = [e["xCoord"] - BX, e["yCoord"] - BY, e["npc"]["hitpoints"] >> 16, e["npc"]["hitpoints"] & 0xFFFF]
        elif ty == 110:
            t["down"] = e["bloatDown"]["downNumber"]
        elif ty == 111:
            t["up"] = 1
        elif ty == 112:
            t["hd"] = [[h["x"] - BX, h["y"] - BY] for h in e["bloatHands"]]
        elif ty == 113:
            t["hs"] = [[h["x"] - BX, h["y"] - BY] for h in e["bloatHands"]]
        elif ty == 6:
            t.setdefault("death", []).append(pi[e["player"]["name"]])
    for t in ticks:
        for k in ("a", "sp", "hd", "hs"):
            if not t[k]:
                del t[k]
    c = idx[s][uuid]
    return {"scale": int(s), "label": label, "source": "Blert", "uuid": uuid,
            "date": c["startTime"][:10], "ticks": c["splits"]["27"]["ticks"],
            "names": names, "t": ticks}


out = []
for s in map(str, range(1, 6)):
    rooms = sorted(clean[s], key=lambda r: r[1])
    fast = rooms[0]
    med = rooms[len(rooms) // 2]
    out.append(compact(s, fast[0], "Blert fastest clean"))
    out.append(compact(s, med[0], "Blert median clean"))
json.dump(out, open(os.path.join(HERE, "replays.json"), "w"), separators=(",", ":"))
print(len(out), sum(len(json.dumps(r)) for r in out) // 1024, "KB")
