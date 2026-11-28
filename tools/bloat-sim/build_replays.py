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
    runs = [(s, team.run(proc, s)) for s in range(120)]
    ok = sorted([(s, r) for s, r in runs if r["ticks"] and r["deaths"] == 0 and r["stuns"] == 0], key=lambda x: x[1]["ticks"])
    import compare_blert as cb
    pos, bt = cb.blert_tracks(proc["source"], scale)
    def on_path(mirror):
        best = (0, -1)
        for s in range(150):
            f = team.run(proc, s, record=True)["frames"]
            n = 0
            while n < len(f) and n in bt and tuple(f[n]["b"][:2]) == ((bt[n][0], 27 - bt[n][1]) if mirror else bt[n]):
                n += 1
            if n > best[1]:
                best = (s, n)
        return best[0]
    picks = [("Sim, Bloat on Blert's path", on_path(False)), ("Sim, mirrored start", on_path(True)), ("Sim, typical run", ok[len(ok) // 2][0])]
    fail = [s for s, r in runs if r["deaths"]]
    if fail:
        picks.append(("Sim, a death (to fix)", fail[0]))
    for label, s in picks:
        r = team.run(proc, s, record=True)
        data.append(add_prelude({"scale": scale, "label": label, "source": "Sim", "uuid": None, "date": None,
                                 "ticks": r["ticks"] or len(r["frames"]), "names": r["names"], "t": r["frames"]}))
import glob
from server_replay import convert
recs = sorted(glob.glob(os.environ.get("BLOAT_RECORDINGS", os.path.join(HERE, "..", "..", ".data", "recordings")) + "/bloat-*.jsonl"))
server = sorted((convert(f) for f in recs), key=lambda r: r["ticks"])
by_scale = {}
for r in server:
    by_scale.setdefault(r["scale"], []).append(r)
for scale, rs in by_scale.items():
    for label, r in (("Server bots, best of %d" % len(rs), rs[0]), ("Server bots, median of %d" % len(rs), rs[len(rs) // 2])):
        r["label"] = label
        data.append(add_prelude(r))
json.dump(data, open(os.path.join(HERE, "viewer", "replays.json"), "w"), separators=(",", ":"))
print(len(data), "replays")
