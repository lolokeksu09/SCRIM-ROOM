import sqlite3
from server.app import Database
from server.match_intelligence import enrich_candidates,role_estimates
from collections import defaultdict

def reference(db,players,stamp):
    ids={p['id'] for p in players}
    pairs=defaultdict(int)
    for p in players:
        estimates=role_estimates(db,p['id'],p['rating'])
        p['role_strength']={r:v['estimate'] for r,v in estimates.items()}
        p['role_uncertainty']={r:v['uncertainty'] for r,v in estimates.items()}
        rows=db.execute("""SELECT mp.match_id,mp.side FROM match_players mp JOIN match_results r ON r.match_id=mp.match_id
          WHERE mp.account_id=? AND r.winner IN ('BLUE','RED') AND r.resolved_at>=?
          ORDER BY r.resolved_at DESC,r.match_id DESC LIMIT 5""",(p['id'],stamp-7*86400)).fetchall()
        p['blue_count']=sum(r['side']=='BLUE' for r in rows)
        p['red_count']=len(rows)-p['blue_count']
        local=defaultdict(int)
        for r in rows:
            for peer in db.execute('SELECT account_id FROM match_players WHERE match_id=? AND side=?',(r['match_id'],r['side'])):
                if peer['account_id']!=p['id'] and peer['account_id'] in ids:
                    local[tuple(sorted((p['id'],peer['account_id'])))]+=1
        for pair,count in local.items():pairs[pair]=max(pairs[pair],count)
    return players,dict(pairs)

def seed(db,n=240,rounds=36,stamp=2000000000):
    roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
    players=[]
    for i in range(n):
        ident=f'p{i:04d}'
        p={'id':ident,'primary':roles[i%5],'secondary':roles[(i+1)%5],'rating':1600+i%7,'joined_at':stamp}
        players.append(p)
        db.execute('INSERT INTO accounts VALUES(?,?,?,?,?,?,?,?,?,?,?)',(ident,ident+'#CI','EUROPE','GRANDMASTER','MASTER',p['primary'],p['secondary'],'token-'+ident,'APPROVED',p['rating'],stamp))
    for rnd in range(rounds):
        # Rotate teams to exercise recent side history, different peer lists and role histories.
        shuffled=players[rnd%n:]+players[:rnd%n]
        for group in range(n//10):
            mid=f'm{rnd:03d}-{group:03d}'
            when=stamp-rnd*30000
            winner='VOID' if rnd%11==0 else ('BLUE' if rnd%2 else 'RED')
            db.execute('INSERT INTO matches VALUES(?,?,?,?,?)',(mid,'EUROPE','CLOSED',when-100,when))
            db.execute('INSERT INTO match_results VALUES(?,?,?,?)',(mid,winner,'CI',when))
            for j,p in enumerate(shuffled[group*10:group*10+10]):
                side='BLUE' if j<5 else 'RED'
                role=roles[(j+rnd)%5]
                db.execute('INSERT INTO match_players VALUES(?,?,?,?,1)',(mid,p['id'],side,role))
                if winner!='VOID':
                    db.execute('INSERT INTO rating_changes VALUES(?,?,?,?)',(mid,p['id'],1600,1612 if side==winner else 1588))
    return players

def test_batch_enrichment_exact_parity_and_query_budget(tmp_path):
    store=Database(str(tmp_path/'batch.db'))
    with store.transaction(write=True) as db:
        players=seed(db)
        # Include both sides, partial peers, >200 candidates and historical VOID rows.
        players=players[:217]
        old=reference(db,[p.copy() for p in players],2000000000)
        queries=[]
        db.set_trace_callback(queries.append)
        new=enrich_candidates(db,[p.copy() for p in players],2000000000)
        db.set_trace_callback(None)
        assert new==old
        assert len(queries)==4,queries
        assert enrich_candidates(db,[],2000000000)==([], {})
