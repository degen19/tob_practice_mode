"""replays.json for the viewer: Blert fastest/median clean rooms per scale plus sim runs, each with the walk-in."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, subprocess, sys
sys.path.insert(0, HERE)
sys.path.insert(0, HERE)
from prelude import add_prelude
import team

subprocess.run([sys.executable, os.path.join(HERE, "extract.py")], check=True)
data = [add_prelude(r) for r in json.load(open(os.path.join(HERE, "replays.json")))]
for f, scale in (("1s", 1), ("2s", 2), ("3s", 3), ("4s", 4), ("5s", 5)):
    proc = json.load(open(f"{HERE}/procedures/{f}.json"))
    runs = [(s, team.run(proc, s)) for s in range(300)]
    ok = sorted([(s, r) for s, r in runs if r["ticks"] and r["deaths"] == 0 and r["stuns"] == 0], key=lambda x: x[1]["ticks"])
    at39 = [x for x in ok if x[1]["downs"] and x[1]["downs"][0] == 39]
    picks = [("Sim, down at 39 (best case)", at39[len(at39) // 2][0]), ("Sim, typical run", ok[len(ok) // 2][0])]
    fail = [s for s, r in runs if r["deaths"]]
    if fail:
        picks.append(("Sim, a death (to fix)", fail[0]))
    for label, s in picks:
        r = team.run(proc, s, record=True)
        data.append(add_prelude({"scale": scale, "label": label, "source": "Sim", "uuid": None, "date": None,
                                 "ticks": r["ticks"] or len(r["frames"]), "names": r["names"], "t": r["frames"]}))
json.dump(data, open(os.path.join(HERE, "viewer", "replays.json"), "w"), separators=(",", ":"))
print(len(data), "replays")
