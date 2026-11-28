"""Non-stallers' line of sight to stallers through the walk (ticks 16 to the first down), and their pnecks."""
import json, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import team


def main(path, rooms=60):
    proc = json.load(open(path))
    stall = [p.get("stall", True) for p in proc["players"]]
    orig = team.Player.__init__
    made = []
    def init(self, *a, **k):
        orig(self, *a, **k)
        made.append(self)
    team.Player.__init__ = init
    los = tot = necks = 0
    for s in range(rooms):
        made.clear()
        r = team.run(proc, s, record=True)
        fr = r["frames"]
        d = next((i for i, f in enumerate(fr) if f.get("down")), len(fr))
        for t in range(16, d):
            ps = fr[t]["p"]
            for i, p in enumerate(ps):
                if stall[i] or not p or not 8 <= p[0] <= 23:
                    continue
                tot += 1
                los += any(stall[j] and q and 8 <= q[0] <= 23 and team.ray_clear(*q, *p) for j, q in enumerate(ps))
        necks += sum(m.necks_used for m in made if not m.stall)
    team.Player.__init__ = orig
    print(f"{proc['name']}: non-staller ticks in a staller's sight {los / max(tot, 1):.0%}, non-staller pnecks/room {necks / rooms:.2f}")


if __name__ == "__main__":
    main(sys.argv[1])
