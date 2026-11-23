"""Deterministic role-aware balancing for ten preselected players.

This module contains no public skill rating system; numeric seeds are used only
for estimating team parity in private matches.
"""
from itertools import product

ROLES = ('BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT')


def arrange(players):
    """Return (blue, red, objective) or None if the ten players cannot fill both sides.

    Players must have id, riot_id, primary, secondary, rating. Exact 5x5 and
    one of each role per side are enforced; no player is duplicated.
    """
    if len(players) != 10 or len({p['id'] for p in players}) != 10:
        return None
    # Reduce branch count by considering less flexible assignments first.
    ordered = sorted(players, key=lambda p: (p['primary'], p['secondary'], p['id']))
    allocations = {role: [] for role in ROLES}
    best = None

    def finish():
        nonlocal best
        # Each role has two assigned participants; choose one for each team.
        for bits in product((0, 1), repeat=5):
            blue, red = [], []
            for role, bit in zip(ROLES, bits):
                a, b = allocations[role]
                blue.append((a, role) if bit == 0 else (b, role))
                red.append((b, role) if bit == 0 else (a, role))
            secondary_count = sum(p['primary'] != role for p, role in blue + red)
            blue_sum = sum(p['rating'] for p, _ in blue)
            red_sum = sum(p['rating'] for p, _ in red)
            role_gaps = sum(abs(blue[i][0]['rating'] - red[i][0]['rating']) for i in range(5))
            score = (abs(blue_sum-red_sum)*2 + role_gaps + secondary_count*55,
                     secondary_count,
                     abs(blue_sum-red_sum))
            if best is None or score < best[2]:
                best = (blue.copy(), red.copy(), score)

    def assign(i):
        if i == len(ordered):
            if all(len(allocations[r]) == 2 for r in ROLES):
                finish()
            return
        p = ordered[i]
        for role in dict.fromkeys([p['primary'], p['secondary']]):
            if role not in allocations or len(allocations[role]) >= 2:
                continue
            allocations[role].append(p)
            assign(i+1)
            allocations[role].pop()

    assign(0)
    return best
