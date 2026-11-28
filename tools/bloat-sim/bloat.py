"""Offline Bloat room: a tick-for-tick port of BloatRoom.kt (walk, speed, turns, downs, hands) for fast sims."""
import random

DOWN_TICKS = 33
TURN_CD = 32
TURN_PER_MILLE = 50
BLOCK = 5
WALK_AFTER_TURN = 3
HAND_FALL = 3
HAND_STUN = 4
TANK = range(13, 19)

LOOP = [(x, 8) for x in range(8, 20)] + [(19, y) for y in range(9, 20)] + \
       [(x, 19) for x in range(18, 7, -1)] + [(8, y) for y in range(18, 8, -1)]
CORNERS = {(8, 8), (19, 8), (19, 19), (8, 19)}
HAND_POINTS = [(7, 2), (8, 2), (13, 7), (13, 8), (7, 13), (8, 13), (2, 7), (2, 8),
               (2, 2), (2, 2), (13, 2), (13, 2), (13, 13), (13, 13), (2, 13), (2, 13)]


def hitpoints(team):
    return 2000 if team >= 5 else 1750 if team == 4 else 1500


def hand_ok(x, y):
    return 0 <= x <= 15 and 0 <= y <= 15 and not (5 <= x <= 10 and 5 <= y <= 10)


def in_tank(x, y):
    return x in TANK and y in TANK


class Room:
    def __init__(self, team, rng=None, start=None, direction=None):
        self.rng = rng or random.Random()
        self.max = self.hp = hitpoints(team)
        self.li = self.rng.randint(0, len(LOOP) - 1) if start is None else LOOP.index(start)
        self.dir = direction if direction is not None else (1 if self.rng.random() < 0.5 else -1)
        self.t = -1
        self.mode = "walk"
        self.walk_t = 0
        self.walk_n = 1
        self.turn_cd = 0
        self.block = 0
        self.speed = 1
        self.walk_after_turn = 0
        self.down_at = 0
        self.attacked_in_down = False
        self.flip_n = 0
        self.below40 = False
        self.hand_gap = 0
        self.last_hands = []
        self.hands = []
        self.defence = 100
        self.events = []

    def ri(self, lo, hi):
        return self.rng.randint(lo, hi)

    @property
    def sw(self):
        return LOOP[self.li]

    @property
    def down(self):
        return DOWN_TICKS - 1 - (self.t - self.down_at) if self.mode == "down" else None

    @property
    def full_damage(self):
        d = self.down
        return d is not None and 0 <= d <= 31

    def stride(self):
        if self.speed < 2 or self.walk_after_turn > 0:
            return 1
        return 1 if LOOP[(self.li + self.dir) % len(LOOP)] in CORNERS else 2

    def tick(self, positions):
        """One server tick. positions: {player: (x, y)} as they stood at the end of last tick. Returns stunned players."""
        self.t += 1
        t = self.t
        if self.mode == "down" and t - self.down_at >= DOWN_TICKS:
            self.mode = "walk"
            self.walk_t = 0
            self.walk_n += 1
            self.hand_gap = 0
            self.events.append((t, "up"))
        if self.mode == "walk":
            self.walk_t += 1
            self.turn_cd += 1
            if self.block > 0:
                self.block -= 1
            pct = self.hp * 100 // self.max
            want = 1 if pct >= 60 else 2 if pct >= 40 else (2 if self.flip_n % 2 == 0 else 1)
            if want != self.speed:
                if pct >= 40:
                    self.block = BLOCK
                self.speed = want
            if self.turn_cd > TURN_CD and self.rng.randrange(1000) < TURN_PER_MILLE:
                self.dir = -self.dir
                self.turn_cd = 0
                self.block = BLOCK
                self.walk_after_turn = WALK_AFTER_TURN
            window = range(39, 47) if self.walk_n == 1 else range(38, 46) if not self.attacked_in_down else range(34, 42)
            walked = self.walk_t - 1
            go_down = False
            if self.block == 0:
                if walked > window[-1]:
                    go_down = True
                elif walked >= window[0] and self.rng.randrange(4) == 0:
                    go_down = True
            if not go_down:
                self.li = (self.li + self.dir * self.stride()) % len(LOOP)
                if self.walk_after_turn > 0:
                    self.walk_after_turn -= 1
            if pct < 90:
                if self.hand_gap <= 0:
                    self.drop_hands()
                    self.hand_gap = 6 if pct >= 40 else 4
                self.hand_gap -= 1
            if go_down:
                self.mode = "down"
                self.down_at = t
                self.attacked_in_down = False
                self.events.append((t, "down", self.walk_t))
        elif t - self.down_at == 29:
            self.defence = 100
        stunned = []
        landing = [h for h in self.hands if h[0] == t]
        self.hands = [h for h in self.hands if h[0] != t]
        for _, tiles in landing:
            for p, pos in positions.items():
                if pos in tiles:
                    stunned.append(p)
            self.events.append((t, "splat", tiles))
        return stunned

    def drop_hands(self):
        cur = []
        for cx, cy in HAND_POINTS:
            x, y = self.ri(cx - 2, cx + 2), self.ri(cy - 2, cy + 2)
            if (x, y) not in self.last_hands and (x, y) not in cur:
                cur.append((x, y))
                continue
            ox, oy, n = x, y, 0
            while True:
                x, y = self.ri(ox - 1, ox + 1), self.ri(oy - 1, oy + 1)
                n += 1
                if not ((not hand_ok(x, y) or (x == ox and y == oy)) and n < 50):
                    break
            if n < 50 and (x, y) not in self.last_hands and (x, y) not in cur:
                cur.append((x, y))
        self.last_hands = cur
        tiles = {(x + 8, y + 8) for x, y in cur}
        self.hands.append((self.t + HAND_FALL, tiles))
        self.events.append((self.t, "drop", tiles))

    def land(self, damage):
        full = self.full_damage
        if full:
            self.attacked_in_down = True
        dealt = damage if full else damage // 2
        after = self.hp - dealt
        if after * 100 < self.max * 40:
            if not self.below40:
                self.below40 = True
                self.flip_n = 0
            self.flip_n += 1
        self.hp = max(0, self.hp - dealt)
        return dealt
