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
    ids={p['id'] for p in players}
    pairs=defaultdict(int)
    for p in players:
        estimates=role_estimates(db,p['id'],p['rating'])
        p['role_strength']={r:v['estimate'] for r,v in estimates.items()}
        p['role_uncertainty']={r:v['uncertainty'] for r,v in estimates.items()}
        recent=db.execute("""SELECT mp.match_id,mp.side FROM match_players mp
          JOIN match_results r ON r.match_id=mp.match_id
          WHERE mp.account_id=? AND r.winner IN ('BLUE','RED') AND r.resolved_at>=?
          ORDER BY r.resolved_at DESC,r.match_id DESC LIMIT 5""",(p['id'],stamp-7*86400)).fetchall()
        p['blue_count']=sum(r['side']=='BLUE' for r in recent)
        p['red_count']=len(recent)-p['blue_count']
        local=defaultdict(int)
        for row in recent:
            for peer in db.execute('SELECT account_id FROM match_players WHERE match_id=? AND side=?',(row['match_id'],row['side'])):
                other=peer['account_id']
                if other!=p['id'] and other in ids: local[tuple(sorted((p['id'],other)))]+=1
        for pair,count in local.items(): pairs[pair]=max(pairs[pair],count)
    return players,dict(pairs)

def save_quality(db,mid,quality):
    db.execute('INSERT INTO match_quality VALUES(?,?)',(mid,json.dumps(quality,separators=(',',':'),sort_keys=True)))

def load_quality(db,mid,full=False):
    row=db.execute('SELECT snapshot FROM match_quality WHERE match_id=?',(mid,)).fetchone()
    if not row: return None
    data=json.loads(row['snapshot'])
    if full: return data
    return {k:data[k] for k in ('algorithm','average_gap','secondary_count','repeated_pairs')}
