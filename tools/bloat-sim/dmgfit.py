"""Mean damage per attack on Bloat, by weapon and walk/down, fitted from Blert HP drops (lags 1-3)."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, collections
import numpy as np

ROOT = TOP
EN = json.load(open(os.path.join(HERE, "enums.json")))
ATK = {int(k): v for k, v in EN["attack"].items()}
clean = json.load(open(f"{ROOT}/clean.json"))
LAGS = (1, 2, 3)
rows, ys, keys = [], [], {}


def key(w, full, lag):
    k = (w, full, lag)
    if k not in keys:
        keys[k] = len(keys)
    return keys[k]


samples = []
for s in clean:
    for u, _ in clean[s]:
        ev = json.load(open(f"{ROOT}/{s}/{u}.json"))
        hp = {}
        for e in ev:
            if e["type"] in (7, 8, 9):
                hp[e["tick"]] = e["npc"]["hitpoints"] >> 16
        downs = [e["tick"] for e in ev if e["type"] == 110]
        full = set()
        for d in downs:
            full.update(range(d, d + 32))
        atk = collections.defaultdict(list)
        for e in ev:
            if e["type"] == 5:
                atk[e["tick"]].append(ATK.get(e["attack"]["type"], "?"))
        end = max(hp)
        death = next((e["tick"] for e in ev if e["type"] == 9), None)
        for t in range(1, end + 1):
            if t not in hp or t - 1 not in hp or (death is not None and t >= death):
                continue
            f = {}
            for lag in LAGS:
                for w in atk.get(t - lag, []):
                    k = key(w, (t - lag) in full, lag)
                    f[k] = f.get(k, 0) + 1
            samples.append((f, hp[t - 1] - hp[t]))

X = np.zeros((len(samples), len(keys)))
y = np.zeros(len(samples))
for i, (f, d) in enumerate(samples):
    for k, v in f.items():
        X[i, k] = v
    y[i] = d
coef, *_ = np.linalg.lstsq(X, y, rcond=None)
inv = {v: k for k, v in keys.items()}
counts = X.sum(0)
tot = collections.defaultdict(lambda: [0.0, 0.0])
for i, c in enumerate(coef):
    w, full, lag = inv[i]
    tot[(w, full)][0] += c
    tot[(w, full)][1] += counts[i]
print("weapon, full damage?, mean damage per attack (sum over lags), lag split, attacks seen")
for (w, full), (c, n) in sorted(tot.items(), key=lambda x: -x[1][1]):
    split = " ".join(f"L{lag}:{coef[keys[(w, full, lag)]]:.0f}" for lag in LAGS if (w, full, lag) in keys)
    print(f"{w:16s} {'down' if full else 'walk'} {c:7.1f}   {split}   n={counts[keys[(w, full, 1)]] if (w, full, 1) in keys else 0:.0f}")
