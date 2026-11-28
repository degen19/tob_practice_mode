"""A server Bloat recording (.data/recordings/bloat-*.jsonl, Blert's event format) as a viewer replay.

python3 server_replay.py <recording.jsonl> [label]   prints the replay JSON (one object) to stdout
"""
import json
import sys

BX, BY = 3280, 4432


def convert(path, label="Server bots"):
    ev = [json.loads(line) for line in open(path) if line.strip()]
    names = sorted({e["player"]["name"] for e in ev if e["type"] == 4})
    pi = {n: i for i, n in enumerate(names)}
    end = max(e["tick"] for e in ev)
    ticks = [{"p": [None] * len(names)} for _ in range(end + 1)]
    death = None
    for e in ev:
        t = ticks[e["tick"]]
        ty = e["type"]
        if ty == 4:
            t["p"][pi[e["player"]["name"]]] = [e["xCoord"] - BX, e["yCoord"] - BY]
        elif ty == 8 and e["npc"]["id"] == 8359:
            hp = e["npc"]["hitpoints"]
            t["b"] = [e["xCoord"] - BX, e["yCoord"] - BY, hp >> 16, hp & 0xFFFF]
            if hp >> 16 == 0 and death is None:
                death = e["tick"]
        elif ty == 5:
            t.setdefault("a", []).append([pi[e["player"]["name"]], e["attack"]["type"]])
        elif ty == 11:
            t.setdefault("sp", []).append([pi[e["player"]["name"]], e["spell"]["type"]])
        elif ty == 110:
            t["down"] = sum(1 for x in ev if x["type"] == 110 and x["tick"] <= e["tick"])
        elif ty == 111:
            t["up"] = 1
        elif ty == 112:
            t["hd"] = [[h["x"] - BX, h["y"] - BY] for h in e["bloatHands"]]
        elif ty == 113:
            t["hs"] = [[h["x"] - BX, h["y"] - BY] for h in e["bloatHands"]]
    scale = len(names)
    return {"scale": scale, "label": label, "source": "Server", "uuid": None, "date": None,
            "ticks": death if death is not None else end, "names": names, "t": ticks[: (death or end) + 1]}


if __name__ == "__main__":
    print(json.dumps(convert(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "Server bots"), separators=(",", ":")))
