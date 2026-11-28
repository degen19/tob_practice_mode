"""A team playing the offline Bloat room from a procedure: who stalls, how many pnecks, swing and spec ticks.

A procedure is JSON (see procedures/*.json): per player
  stall        true: swing, stand still 3 ticks, step, swing (the 5-tick cycle); false: follow him every tick
  necks        phoenix necklaces carried; salve is worn on the swing tick, a pneck the rest of the cycle
  hp           hitpoints on entry (boosted)
  first_swing  walk tick of the first scythe
  walk_specs   [{"weapon": "BGS_SPEC", "tick": 2}] specs during the walk, on that room tick
  down_specs   [{"weapon": "CLAW_SPEC", "at": 10}] specs in a down, ticks after he goes down
"""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.environ.get("BLOAT_TOP", os.path.join(HERE, "top"))
import json, random, sys
sys.path.insert(0, HERE)
from bloat import Room, in_tank, LOOP, CORNERS
from combat import Weapons

SPEC_COST = {"CLAW_SPEC": 50, "BGS_SPEC": 50, "CHALLY_SPEC": 30, "ZCB_SPEC": 75}
SPEED = {"SCYTHE": 5, "CLAW_SPEC": 4, "BGS_SPEC": 6, "CHALLY_SPEC": 7, "ZCB_SPEC": 5}
RING = [(x, y) for x in range(12, 20) for y in range(12, 20) if not in_tank(x, y)]
RING_SET = set(RING)
STALL_LOCK = 3
FLY_MAX = 20
SAFE_SALVE_HP = 20 + FLY_MAX * 3 // 4


def hit(p, dmg):
    """A hit landing on a player; a phoenix necklace procs below 20% (healing 30%) and clears every held hit."""
    p.hp -= dmg
    if 0 < p.hp and p.hp * 5 < p.max_hp and p.neck == "pneck":
        p.hp += p.max_hp * 3 // 10
        p.necks -= 1
        p.necks_used += 1
        p.neck = None
        p.held.clear()
        return True
    return False


def ray_clear(sx, sy, ex, ey):
    dx, dy = ex - sx, ey - sy
    ax, ay = abs(dx), abs(dy)
    if ax > ay:
        xi = 1 if dx > 0 else -1
        slope = (dy * 65536) // ax if dy >= 0 else -((-dy * 65536) // ax)
        x, yf = sx, sy * 65536 + 32768 - (1 if dy < 0 else 0)
        while x != ex:
            x += xi
            y = yf >> 16
            if in_tank(x, y):
                return False
            yf += slope
            ny = yf >> 16
            if ny != y and in_tank(x, ny):
                return False
    elif ay > 0:
        yi = 1 if dy > 0 else -1
        slope = (dx * 65536) // ay if dx >= 0 else -((-dx * 65536) // ay)
        y, xf = sy, sx * 65536 + 32768 - (1 if dx < 0 else 0)
        while y != ey:
            y += yi
            x = xf >> 16
            if in_tank(x, y):
                return False
            xf += slope
            nx = xf >> 16
            if nx != x and in_tank(nx, y):
                return False
    return True


def footprint(bx, by):
    return {(x, y) for x in range(bx, bx + 5) for y in range(by, by + 5)}


def in_reach(p, bx, by):
    """Melee reach: under his inner edge or beside his 5x5 (not diagonal to a corner)."""
    x, y = p
    if (x, y) in footprint(bx, by):
        return True
    return (bx <= x <= bx + 4 and y in (by - 1, by + 5)) or (by <= y <= by + 4 and x in (bx - 1, bx + 5))


def route(p, goal, bx, by, avoid=()):
    """Tiles from p to goal round the tank (8-way, the game's corner rule), tiles under him or in his sight costing more."""
    import heapq
    if p == goal:
        return [p]
    under = footprint(bx, by)
    best = {p: 0}
    prev = {}
    q = [(0, p)]
    while q:
        c, cur = heapq.heappop(q)
        if cur == goal:
            break
        if c > best[cur]:
            continue
        x, y = cur
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                n = (x + dx, y + dy)
                if (dx or dy) and 8 <= n[0] <= 23 and 8 <= n[1] <= 23 and not in_tank(*n):
                    if dx and dy and (in_tank(x + dx, y) or in_tank(x, y + dy)):
                        continue
                    w = 1 + (5 if n in under else 0) + (6 if n in avoid else 0) + (0 if n in RING_SET else 0.5)
                    if c + w < best.get(n, 10 ** 9):
                        best[n] = c + w
                        prev[n] = cur
                        heapq.heappush(q, (c + w, n))
    if goal not in prev:
        return [p, goal]
    path = [goal]
    while path[-1] != p:
        path.append(prev[path[-1]])
    return path[::-1]


def steps_to(p, goal, bx, by):
    """Fewest steps from p to goal round the tank (8-way, corners not cut)."""
    from collections import deque
    if p == goal:
        return 0
    seen = {p: 0}
    q = deque([p])
    while q:
        x, y = q.popleft()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                n = (x + dx, y + dy)
                if (dx or dy) and n not in seen and 8 <= n[0] <= 23 and 8 <= n[1] <= 23 and not in_tank(*n):
                    if dx and dy and (in_tank(x + dx, y) or in_tank(x, y + dy)):
                        continue
                    seen[n] = seen[(x, y)] + 1
                    if n == goal:
                        return seen[n]
                    q.append(n)
    return 10 ** 6


def safe_step(p, goal, landing_next, bx=None, by=None, avoid=()):
    """Up to 2 tiles toward the goal, ending the tick off any tile a hand lands on next tick."""
    if bx is not None:
        path = route(p, goal, bx, by, avoid)
        nxt = path[min(2, len(path) - 1)]
        if len(path) > 2 and path[1] not in landing_next and nxt in landing_next:
            nxt = path[1]
    else:
        nxt = step_towards(p, goal, 2)
    if nxt not in landing_next:
        return nxt
    opts = [(x, y) for x in range(p[0] - 2, p[0] + 3) for y in range(p[1] - 2, p[1] + 3)
            if 8 <= x <= 23 and 8 <= y <= 23 and not in_tank(x, y) and (x, y) not in landing_next]
    return min(opts, key=lambda c: max(abs(c[0] - goal[0]), abs(c[1] - goal[1]))) if opts else nxt


def step_towards(p, goal, n):
    x, y = p
    for _ in range(n):
        if (x, y) == goal:
            break
        nx = x + (goal[0] > x) - (goal[0] < x)
        ny = y + (goal[1] > y) - (goal[1] < y)
        if in_tank(nx, ny):
            if not in_tank(nx, y):
                ny = y
            elif not in_tank(x, ny):
                nx = x
            else:
                break
        x, y = nx, ny
    return (x, y)


class Player:
    def __init__(self, i, spec, pos):
        spec = dict(spec)
        self.i = i
        self.name = spec.get("name", f"P{i + 1}")
        self.stall = spec.get("stall", True)
        self.necks = spec.get("necks", 6)
        self.hp = spec.get("hp", 115)
        self.first = spec.get("first_swing", 14)
        self.walk_specs = {s["tick"]: s["weapon"] for s in spec.get("walk_specs", [])}
        self.down_spec_list = spec.get("down_specs", [])
        self.down_specs = {}
        self.rewalk_attack = spec.get("rewalk_attack", True)
        self.walk_swings = spec.get("walk_swings")
        self.walk_sides = spec.get("walk_sides", [])
        self.down_start = spec.get("down_start", [0])
        self.pray = spec.get("pray_missiles", True)
        self.pos = pos
        self.enter = spec.get("enter", 0)
        self.energy = spec.get("energy", 100)
        self.salve = spec.get("salve", "always" if self.stall else "never")
        self.next_attack = 0
        self.neck = "salve"
        self.dead = False
        self.stunned_until = -1
        self.necks_used = 0
        self.held = []
        self.last_swing = -1
        self.locked_until = -1
        self.stall_cast = -1
        self.stalls = 0
        self.max_hp = 99


def run(proc, seed, record=False, weapons=None):
    rng = random.Random(seed)
    team = len(proc["players"])
    start, direction = None, None
    if "entry" in proc:
        side = rng.choice((8, 19))
        start = (proc["entry"]["x"], side)
        east = proc["entry"].get("heading", "east") == "east"
        direction = (1 if side == 8 else -1) if east else (-1 if side == 8 else 1)
    if start is not None:
        start = LOOP[(LOOP.index(start) - direction) % len(LOOP)]
    room = Room(team, rng, start, direction)
    w = weapons or Weapons()
    factor = proc.get("damage_factor", 1.0)
    if factor != 1.0:
        base = w.hits
        w.hits = lambda wpn, d, r: [(a, int(x * factor)) for a, x in base(wpn, d, r)]
    entry = [(25, 14), (25, 15), (25, 16), (25, 17), (25, 15)]
    west = [(6, 14), (6, 15), (6, 16), (6, 17)]
    ps = []
    for i, sp in enumerate(proc["players"]):
        ps.append(Player(i, sp, west[i % 4] if sp.get("gate") == "west" else entry[i]))
    landing = {}
    swarm = None
    frames = []
    stuns = 0
    death_tick = None
    for t in range(0, 400):
        positions = {p.i: p.pos for p in ps if not p.dead and 8 <= p.pos[0] < 24}
        seen_from = room.sw
        for i in room.tick(positions):
            p = ps[i]
            p.stunned_until = t + 4
            hit(p, rng.randint(30, 50))
            stuns += 1
        bx, by = room.sw
        acts, spells = [], []
        for dmg, wpn in landing.pop(t, []):
            room.land(dmg)
            if wpn == "BGS_SPEC":
                room.defence = max(0, room.defence - (dmg if room.full_damage else dmg // 2))
        if room.hp <= 0:
            death_tick = t
            if record:
                frames.append(frame(t, ps, room, acts, spells))
            break
        down = room.down
        hands_by = {}
        for when, tiles in room.hands:
            hands_by.setdefault(when, set()).update(tiles)
        seen_now = {c for c in OUTER if ray_clear(bx + 2, by + 2, *c)}
        for p in ps:
            if p.dead or t < p.stunned_until:
                continue
            if p.pos[0] >= 24 or p.pos[0] <= 7:
                if t >= p.enter:
                    side_x = 23 if p.pos[0] >= 24 else 8
                    first_act = min([p.first] + [k for k in p.walk_specs if k >= t])
                    p.pos = (side_x, 15)
                    aim = plan_tile(p, room, t, hands_by, [], ready=max(first_act, t + 1), side="any", still=True)
                    p.pos = (side_x, min(max(aim[1], 14), 17))
                continue
            hold = 5 if p.stall and down is None else 1
            danger = set().union(*[v for when, v in hands_by.items() if t < when <= t + hold]) if hands_by else set()
            stallers = [q.pos for q in ps if q.stall and not q.dead and 8 <= q.pos[0] < 24]
            rewalk = down is None and room.walk_n > 1
            nxt = None
            if p.walk_swings is not None and down is None and room.walk_n == 1:
                nxt = min([x for x in p.walk_swings if x > p.last_swing] or [10 ** 6])
            hiding = (t < p.first and down is None) or (rewalk and not p.rewalk_attack) or (nxt is not None and nxt - t > 4 and t >= p.next_attack - 1)
            first_act = min([p.first] + [k for k in p.walk_specs if k >= t])
            if down is None and t <= first_act and first_act - t <= 6:
                target = plan_tile(p, room, t, hands_by, stallers, ready=first_act, side="any", still=True)
            elif nxt is not None and nxt < 10 ** 6 and not p.stall and t >= p.first and down is None:
                target = sneak(p, room, t, nxt, hands_by, stallers)
            elif hiding:
                danger = set().union(*[v for when, v in hands_by.items() if t < when <= t + 3]) if hands_by else set()
                target = hide_tile(p, room, danger, 5 if nxt is None else min(nxt - t, 8))
            elif not p.stall and down is None:
                target = plan_tile(p, room, t, hands_by, stallers)
            else:
                target = goal_tile(p, bx, by, danger, None if p.stall else stallers)
            ready = t >= p.next_attack
            wpn = None
            if down is not None:
                k = t - room.down_at
                if k == 0:
                    n_down = sum(1 for e in room.events if e[1] == "down")
                    p.down_specs = {d["at"]: d["weapon"] for d in p.down_spec_list if d.get("down", n_down) == n_down}
                    p.next_attack = max(p.next_attack, t + p.down_start[min(n_down, len(p.down_start)) - 1])
                wpn = p.down_specs.get(k, "SCYTHE") if t >= p.next_attack else None
                if k in p.down_specs and not ready:
                    p.down_specs[k + 1] = p.down_specs.pop(k)
            elif t in p.walk_specs and ready:
                wpn = p.walk_specs[t]
            elif ready and t >= p.first and not (rewalk and not p.rewalk_attack) and (nxt is None or nxt <= t):
                wpn = "SCYTHE"
            landing_soon = set().union(*[v for when, v in hands_by.items() if t < when <= t + 3]) if hands_by else set()
            moving = hiding or not p.stall or down is not None or t < p.first or t >= p.next_attack or p.pos in landing_soon
            if moving:
                p.pos = safe_step(p.pos, target, hands_by.get(t + 1, set()), bx, by, seen_now)
            elif p.pos in hands_by.get(t + 1, set()):
                p.pos = safe_step(p.pos, p.pos, hands_by.get(t + 1, set()))
            if wpn in SPEC_COST and p.energy < SPEC_COST[wpn]:
                wpn = "SCYTHE" if (down is not None or t >= p.first) else None
            if wpn and in_reach(p.pos, bx, by):
                p.energy -= SPEC_COST.get(wpn, 0)
                if p.necks == 0 or down is not None or (p.salve == "always" and p.hp >= SAFE_SALVE_HP):
                    p.neck = "salve"
                    hits = w.hits(wpn, room.defence, rng)
                else:
                    hits = [(a, x * 5 // 6) for a, x in w.hits(wpn, room.defence, rng)]
                for delay, dmg in hits:
                    landing.setdefault(t + delay, []).append((dmg, wpn))
                p.next_attack = t + SPEED[wpn]
                p.last_swing = t
                acts.append([p.i, wpn])
                if p.stall and down is None:
                    p.stall_cast = t + 1
            elif p.necks > 0 and p.neck != "pneck":
                p.neck = "pneck"
            if t == p.stall_cast:
                spells.append([p.i, "HUMIDIFY"])
                p.locked_until = t + STALL_LOCK
                p.stalls += 1
        if t and t % 50 == 0:
            for p in ps:
                p.energy = min(100, p.energy + 10)
        if room.mode == "walk":
            seen = [p for p in ps if not p.dead and 8 <= p.pos[0] < 24 and ray_clear(seen_from[0] + 2, seen_from[1] + 2, *p.pos)]
            if swarm not in seen:
                prev = swarm
                bounce = [q for q in seen if prev is not None and not prev.dead and ray_clear(*prev.pos, *q.pos)]
                swarm = bounce[0] if bounce else (rng.choice(seen) if seen else None)
            if swarm:
                roll = rng.randint(1, FLY_MAX)
                dmg = roll * 3 // 4 if swarm.pray else roll
                if t <= swarm.locked_until:
                    swarm.held.append(dmg)
                else:
                    hit(swarm, dmg)
        for p in ps:
            if p.dead:
                continue
            if p.held and t >= p.locked_until:
                bunch, p.held = p.held, []
                for dmg in bunch:
                    if hit(p, dmg) or p.hp <= 0:
                        break
            if p.hp <= 0:
                p.dead = True
                p.cause = (t, p.neck, p.necks, t < p.stunned_until)
        if record:
            frames.append(frame(t, ps, room, acts, spells))
        if all(p.dead for p in ps):
            break
    result = {"ticks": death_tick, "deaths": sum(p.dead for p in ps), "stuns": stuns,
              "necks": sum(p.necks_used for p in ps), "downs": [e[0] for e in room.events if e[1] == "down"]}
    if record:
        result["frames"] = frames
        result["names"] = [p.name for p in ps]
    return result


def hide_tile(p, room, danger, lead):
    """Out of his sight now and a tick on, clear of hands, near where he'll be in `lead` ticks; hugging the tank if it can."""
    fx, fy = future_sw(room, lead)
    bx, by = room.sw
    nx, ny = future_sw(room, 1)
    near = [c for c in OUTER if max(abs(c[0] - p.pos[0]), abs(c[1] - p.pos[1])) <= 6]
    def cost(c):
        seen = ray_clear(bx + 2, by + 2, *c) or ray_clear(nx + 2, ny + 2, *c)
        return (c in danger, seen, c in footprint(bx, by), abs(c[0] - fx - 2) + abs(c[1] - fy - 2) + (0 if c in RING_SET else 2))
    return min(near or OUTER, key=cost)


OUTER = [(x, y) for x in range(8, 24) for y in range(8, 24) if not in_tank(x, y)]


def goal_tile(p, bx, by, danger, away=None):
    if away:
        cand = [c for c in OUTER if in_reach(c, bx, by) and c not in footprint(bx, by)]
        safe = [c for c in cand if c not in danger] or cand
        far = lambda c: -min(max(abs(c[0] - a[0]), abs(c[1] - a[1])) for a in away)
        return min(safe, key=lambda c: (far(c) < -2 and 0 or 1, max(abs(c[0] - p.pos[0]), abs(c[1] - p.pos[1])), far(c)))
    cand = [c for c in RING if c in footprint(bx, by) or in_reach(c, bx, by)]
    if not cand:
        cand = [c for c in RING]
    safe = [c for c in cand if c not in danger] or cand
    cx, cy = bx + 2, by + 2
    return min(safe, key=lambda c: (max(abs(c[0] - p.pos[0]), abs(c[1] - p.pos[1])), abs(c[0] - cx) + abs(c[1] - cy)))


def future_sw(room, k):
    """Where his south-west tile will be k ticks on, if he keeps walking (no turn, no down)."""
    li, war = room.li, room.walk_after_turn
    for _ in range(k):
        nxt = LOOP[(li + room.dir) % len(LOOP)]
        stride = 1 if room.speed < 2 or war > 0 or nxt in CORNERS else 2
        li = (li + room.dir * stride) % len(LOOP)
        war = max(0, war - 1)
    return LOOP[li]


def plan_tile(p, room, t, hands_by, stallers, ready=None, side="front", still=False):
    """A non-staller's tile for its next swing: in reach of where he'll be when the scythe is ready, hugging the tank,
    out of his sight and the stallers' while it waits, clear of hands, and reachable in time (running 2 tiles a tick)."""
    ready = max(p.next_attack, t) if ready is None else ready
    fbx, fby = future_sw(room, ready - t)
    nbx, nby = room.sw
    hand_at = set().union(*[v for when, v in hands_by.items() if ready <= when <= ready + 1]) if hands_by else set()
    best, best_cost = None, None
    ax, ay = future_sw(room, ready - t + 1)
    heading = (ax - fbx, ay - fby)
    for c in OUTER:
        if not in_reach(c, fbx, fby) or c in footprint(fbx, fby):
            continue
        if max(abs(c[0] - p.pos[0]), abs(c[1] - p.pos[1])) > 2 * (ready - t + 1):
            continue
        d = steps_to(p.pos, c, nbx, nby)
        if d > 2 * (ready - t + 1):
            continue
        seen_now = ray_clear(nbx + 2, nby + 2, *c)
        bounce = any(ray_clear(*a, *c) for a in stallers)
        ring = 0 if c in RING_SET else 1
        ahead = -((c[0] - fbx - 2) * heading[0] + (c[1] - fby - 2) * heading[1])
        if side == "back":
            ahead = -ahead
        elif side == "any":
            ahead = 0
        cost = (c in hand_at, ahead, d, ring, seen_now and bounce, seen_now) if still else (c in hand_at, ahead, ring, seen_now and bounce, seen_now, d)
        if best_cost is None or cost < best_cost:
            best, best_cost = c, cost
    return best or goal_tile(p, nbx, nby, hand_at, stallers)


def sneak(p, room, t, swing_at, hands_by, stallers):
    """A long gap between swings: route to the next swing's tile out of his sight (round the tank if need be), and wait
    on the last tile of that route he can't see until it's time to step out and swing."""
    bx, by = room.sw
    k = p.walk_swings.index(swing_at) if swing_at in p.walk_swings else -1
    side = p.walk_sides[k] if 0 <= k < len(p.walk_sides) else "front"
    goal = plan_tile(p, room, t, hands_by, stallers, ready=swing_at, side=side)
    seen = {c for c in OUTER if ray_clear(bx + 2, by + 2, *c)}
    path = route(p.pos, goal, bx, by, avoid=seen)
    left = swing_at - t
    if len(path) - 1 > 2 * (left + 1):
        path = route(p.pos, goal, bx, by)
    best = goal
    for i, c in enumerate(path):
        if c not in seen and len(path) - 1 - i <= 2 * (left - 1):
            best = c
    return best


def duck_tile(p, bx, by, danger, stallers):
    """Between swings a non-staller steps out of his sight, and out of the stallers' (so the flies can't bounce over)."""
    near = [c for c in OUTER if max(abs(c[0] - p.pos[0]), abs(c[1] - p.pos[1])) <= 4 and c not in danger and c not in footprint(bx, by)]
    def cost(c):
        seen = ray_clear(bx + 2, by + 2, *c)
        bounce = any(ray_clear(*a, *c) for a in stallers)
        return (seen, bounce, max(abs(c[0] - p.pos[0]), abs(c[1] - p.pos[1])))
    return min(near, key=cost) if near else p.pos


def frame(t, ps, room, acts, spells):
    f = {"p": [None if p.dead else list(p.pos) for p in ps], "b": [room.sw[0], room.sw[1], max(room.hp, 0), room.max]}
    if acts:
        f["a"] = acts
    if spells:
        f["sp"] = spells
    ev = [e for e in room.events if e[0] == t]
    for e in ev:
        if e[1] == "down":
            f["down"] = sum(1 for x in room.events if x[1] == "down" and x[0] <= t)
        elif e[1] == "up":
            f["up"] = 1
        elif e[1] == "drop":
            f["hd"] = [list(x) for x in e[2]]
        elif e[1] == "splat":
            f["hs"] = [list(x) for x in e[2]]
    dead = [p.i for p in ps if p.dead and p.hp <= 0 and getattr(p, "_shown", False) is False]
    for i in dead:
        ps[i]._shown = True
    if dead:
        f["death"] = dead
    return f


if __name__ == "__main__":
    proc = json.load(open(sys.argv[1]))
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    rs = [run(proc, s) for s in range(n)]
    done = [r["ticks"] for r in rs if r["ticks"] is not None and r["deaths"] == 0]
    import statistics
    print(f"{proc['name']}: success {len(done) / n:.0%}, median {statistics.median(done) if done else '-'}, "
          f"best {min(done) if done else '-'}, deaths/room {sum(r['deaths'] for r in rs) / n:.2f}, "
          f"stuns/room {sum(r['stuns'] for r in rs) / n:.2f}, pnecks/room {sum(r['necks'] for r in rs) / n:.1f}")
