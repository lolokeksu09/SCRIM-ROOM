"""Role estimates and immutable selection diagnostics, derived only from confirmed club results.

The uncertainty value is a heuristic sample-size indicator, not a calibrated interval.
No mutation of Elo, no retroactive scoring of historical matches.
"""
import json
import math
from collections import defaultdict
from server.matchmaking import ROLES

def estimate(base,history):
    # Elo delta / 24 approximates the residual of the result against the old
    # team's expected outcome. Center the role residual on the player's own
    # recent overall residual to avoid simply counting their global Elo twice.
    recent=history[:30]
    mean=sum(r['delta'] for r in recent)/len(recent) if recent else 0
    roles={}
    for role in ROLES:
        subset=[r['delta'] for r in recent if r['role']==role]
        n=len(subset)
        residual=(sum(subset)-n*mean)/(n+8)
        adjustment=round(max(-60,min(60,5*residual)))
        roles[role]={'estimate':base+adjustment,'sample':n,'uncertainty':60+round(140/math.sqrt(n+1))}
    return roles

def role_estimates(db,ident,base):
    rows=db.execute("""SELECT mp.role,(rc.after_rating-rc.before_rating) AS delta
      FROM rating_changes rc JOIN match_results r ON r.match_id=rc.match_id
      JOIN match_players mp ON mp.match_id=rc.match_id AND mp.account_id=rc.account_id
      WHERE rc.account_id=? AND r.winner IN ('BLUE','RED')
      ORDER BY r.resolved_at DESC,r.match_id DESC LIMIT 30""",(ident,)).fetchall()
    return estimate(base,[dict(r) for r in rows])

def enrich_candidates(db,players,stamp):
    """Batch history queries with bounded parameter lists and exact previous semantics."""
    ids={p['id'] for p in players}
    history=defaultdict(list)
    recent=defaultdict(list)
    local=defaultdict(int)
    ordered=sorted(ids)
    for offset in range(0,len(ordered),200):
        chunk=ordered[offset:offset+200]
        marks=','.join('?' for _ in chunk)
        for r in db.execute(f"""WITH ranked AS (
          SELECT rc.account_id,mp.role,rc.after_rating-rc.before_rating AS delta,
            row_number() OVER(PARTITION BY rc.account_id ORDER BY r.resolved_at DESC,r.match_id DESC) AS rn
          FROM rating_changes rc JOIN match_results r ON r.match_id=rc.match_id
          JOIN match_players mp ON mp.match_id=rc.match_id AND mp.account_id=rc.account_id
          WHERE rc.account_id IN ({marks}) AND r.winner IN ('BLUE','RED'))
          SELECT account_id,role,delta FROM ranked WHERE rn<=30 ORDER BY account_id,rn""",chunk):
            history[r['account_id']].append(dict(r))
        for r in db.execute(f"""WITH ranked AS (
          SELECT mp.account_id,mp.match_id,mp.side,
            row_number() OVER(PARTITION BY mp.account_id ORDER BY r.resolved_at DESC,r.match_id DESC) AS rn
          FROM match_players mp JOIN match_results r ON r.match_id=mp.match_id
          WHERE mp.account_id IN ({marks}) AND r.winner IN ('BLUE','RED') AND r.resolved_at>=?)
          SELECT x.account_id,x.match_id,x.side,p.account_id AS peer
          FROM ranked x JOIN match_players p ON p.match_id=x.match_id AND p.side=x.side
          WHERE x.rn<=5 ORDER BY x.account_id,x.rn""",[*chunk,stamp-7*86400]):
            ident=r['account_id']
            if r['peer']==ident:
                recent[ident].append(r['side'])
            elif r['peer'] in ids:
                local[(ident,r['peer'])]+=1
    pairs=defaultdict(int)
    for (ident,peer),count in local.items():
        key=tuple(sorted((ident,peer)))
        pairs[key]=max(pairs[key],count)
    for p in players:
        ident=p['id']
        estimates=estimate(p['rating'],history[ident])
        p['role_strength']={r:v['estimate'] for r,v in estimates.items()}
        p['role_uncertainty']={r:v['uncertainty'] for r,v in estimates.items()}
        p['blue_count']=recent[ident].count('BLUE')
        p['red_count']=len(recent[ident])-p['blue_count']
    return players,dict(pairs)


def save_quality(db,mid,quality):
    db.execute('INSERT INTO match_quality VALUES(?,?)',(mid,json.dumps(quality,separators=(',',':'),sort_keys=True)))

def load_quality(db,mid,full=False):
    row=db.execute('SELECT snapshot FROM match_quality WHERE match_id=?',(mid,)).fetchone()
    if not row: return None
    data=json.loads(row['snapshot'])
    if full: return data
    return {k:data[k] for k in ('algorithm','average_gap','secondary_count','repeated_pairs')}
