"""Hitsplat models for the weapons top teams use on Bloat. Rolls follow the game's accuracy formula; maxes are
fitted so each weapon's mean on Bloat matches Blert (see dmgfit.py)."""
import random

SLASH_DEF = -20
CRUSH_DEF = -20
RANGED_DEF = 800


def chance(att, defence_roll):
    if att > defence_roll:
        return 1 - (defence_roll + 2) / (2 * (att + 1))
    return att / (2 * (defence_roll + 1))


class Weapons:
    def __init__(self, scythe_max=65, scythe_att=42681, claw_max=60, claw_att=40000, bgs_max=90,
                 bgs_att=38000, chally_max=118, chally_att=38000, zcb_spec=110):
        self.p = dict(scythe_max=scythe_max, scythe_att=scythe_att, claw_max=claw_max, claw_att=claw_att,
                      bgs_max=bgs_max, bgs_att=bgs_att, chally_max=chally_max, chally_att=chally_att, zcb_spec=zcb_spec)

    def def_roll(self, defence, bonus):
        return (defence + 9) * (bonus + 64)

    def hits(self, weapon, defence, rng):
        """[(land delay, damage)] hitsplats for one attack against Bloat at this Defence level."""
        p = self.p
        if weapon == "SCYTHE":
            c = chance(p["scythe_att"], self.def_roll(defence, SLASH_DEF))
            m = p["scythe_max"]
            return [(1, rng.randint(0, mx) if rng.random() < c else 0) for mx in (m, m // 2, m // 4)]
        if weapon == "CLAW_SPEC":
            c = chance(p["claw_att"], self.def_roll(defence, SLASH_DEF))
            m = p["claw_max"]
            for k in range(4):
                if rng.random() < c:
                    if k == 0:
                        a = rng.randint(m // 2, m - 1)
                        return [(1, a), (1, a // 2), (2, a // 4), (2, a // 4 + 1)]
                    if k == 1:
                        a = rng.randint(3 * m // 8, 7 * m // 8)
                        return [(1, 0), (1, a), (2, a // 2), (2, a // 2 + 1)]
                    if k == 2:
                        a = rng.randint(m // 4, 3 * m // 4)
                        return [(1, 0), (1, 0), (2, a), (2, a + 1)]
                    a = rng.randint(m // 4, 5 * m // 4)
                    return [(1, 0), (1, 0), (2, 0), (2, a)]
            return [(1, 0), (1, 0), (2, rng.randint(0, 1)), (2, rng.randint(0, 1))]
        if weapon == "BGS_SPEC":
            c = chance(p["bgs_att"] * 2, self.def_roll(defence, CRUSH_DEF))
            return [(1, rng.randint(0, p["bgs_max"]) if rng.random() < c else 0)]
        if weapon == "CHALLY_SPEC":
            c = chance(int(p["chally_att"] * 1.75), self.def_roll(defence, SLASH_DEF))
            return [(1, rng.randint(0, p["chally_max"]) if rng.random() < c else 0) for _ in range(2)]
        if weapon == "ZCB_SPEC":
            return [(3, p["zcb_spec"])]
        return []
