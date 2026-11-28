"""Adds the walk-in before a replay's tick 0: everyone outside the gates, the first in passing through on tick 0,
and Bloat patrolling his loop (at a walk, the same way round) back from where he stood at the room's start."""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import sys
sys.path.insert(0, HERE)
from bloat import LOOP

PRE = 4


def gate_side(p):
    x, y = p
    if x >= 24:
        return [x, y]
    if x <= 7:
        return [x, y]
    if x >= 16:
        return [25, min(max(y, 14), 17)]
    return [6, min(max(y, 14), 17)]


def add_prelude(r):
    t = r["t"]
    b0, b1 = t[0].get("b"), next((f.get("b") for f in t[1:] if f.get("b")), None)
    k0 = LOOP.index((b0[0], b0[1])) if (b0[0], b0[1]) in LOOP else 0
    d = 1
    if b1 and (b1[0], b1[1]) in LOOP:
        k1 = LOOP.index((b1[0], b1[1]))
        d = 1 if (k1 - k0) % len(LOOP) in (1, 2) else -1
    first = t[0]["p"]
    pre = []
    for k in range(PRE, 0, -1):
        bx, by = LOOP[(k0 - d * k) % len(LOOP)]
        ps = [gate_side(p) if p else None for p in first]
        pre.append({"p": ps, "b": [bx, by, b0[2], b0[3]]})
    r["t"] = pre + t
    r["pre"] = PRE
    return r
