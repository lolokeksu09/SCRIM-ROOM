"""Bounded deterministic private-match balancing. No public rating or Riot telemetry."""
from itertools import product, combinations

ROLES = ('BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT')
ALGORITHM = 'role-aware-v2'

def weights(wait_seconds):
    # Gradually relax preference/diversity penalties, not role feasibility or skill parity.
    step=min(4,max(0,int(wait_seconds)//60))
    return {'secondary':55-5*step,'repeat':16-2*step,'uncertainty':1}

def balance_metrics(blue,red,repeat_pairs=None,wait_seconds=0):
    repeat_pairs=repeat_pairs or {}
    w=weights(wait_seconds)
    def strength(p,r): return p.get('role_strength',{}).get(r,p['rating'])
    def uncertainty(p,r): return p.get('role_uncertainty',{}).get(r,200)
    bs=sum(strength(p,r) for p,r in blue); rs=sum(strength(p,r) for p,r in red)
    bu=sum(uncertainty(p,r) for p,r in blue); ru=sum(uncertainty(p,r) for p,r in red)
    secondary=sum(p['primary']!=r for p,r in blue+red)
    gaps={r:abs(strength(bp,r)-strength(rp,r)) for (bp,r),(rp,_) in zip(blue,red)}
    repeat_cost=0; repeated=0
    for team in (blue,red):
        for (p,_),(q,_) in combinations(team,2):
            n=min(3,max(0,repeat_pairs.get(tuple(sorted((p['id'],q['id']))),0)))
            repeat_cost+=n; repeated+=int(n>0)
    score=2*abs(bs-rs)+sum(gaps.values())+secondary*w['secondary']+abs(bu-ru)*w['uncertainty']+repeat_cost*w['repeat']
    return {'algorithm':ALGORITHM,'blue_strength':round(bs/5,1),'red_strength':round(rs/5,1),
        'average_gap':round(abs(bs-rs)/5,1),'role_gaps':gaps,'role_gap_total':sum(gaps.values()),
        'uncertainty_gap':abs(bu-ru),'secondary_count':secondary,'repeated_pairs':repeated,
        'repeat_cost':repeat_cost,'oldest_wait_seconds':max(0,int(wait_seconds)),'weights':w,'score':score}

def arrange(players,repeat_pairs=None,wait_seconds=0):
    """Return (blue, red, objective) for the earliest feasible ten selected by the caller."""
    if len(players)!=10 or len({p['id'] for p in players})!=10: return None
    ordered=sorted(players,key=lambda p:(p['primary'],p['secondary'],p['id']))
    allocations={role:[] for role in ROLES}
    best=None
    def finish():
        nonlocal best
        # A team and its mirror have identical objectives. Consider one of each pair,
        # then orient deterministically using recent side history as a final tie-break.
        for bits in product((0,1),repeat=4):
            blue,red=[],[]
            for role,bit in zip(ROLES,(0,)+bits):
                a,b=allocations[role]
                blue.append((a,role) if bit==0 else (b,role))
                red.append((b,role) if bit==0 else (a,role))
            def side_cost(b,r):
                return sum(p.get('blue_count',0) for p,_ in b)+sum(p.get('red_count',0) for p,_ in r)
            if side_cost(red,blue)<side_cost(blue,red):
                blue,red=red,blue
            m=balance_metrics(blue,red,repeat_pairs,wait_seconds)
            objective=(m['score'],m['secondary_count'],m['average_gap'],side_cost(blue,red))
            if best is None or objective<best[2]:
                best=(blue.copy(),red.copy(),objective)
    def assign(i):
        if i==len(ordered):
            if all(len(allocations[r])==2 for r in ROLES): finish()
            return
        p=ordered[i]
        for role in dict.fromkeys([p['primary'],p['secondary']]):
            if role not in allocations or len(allocations[role])>=2: continue
            allocations[role].append(p)
            assign(i+1)
            allocations[role].pop()
    assign(0)
    return best
