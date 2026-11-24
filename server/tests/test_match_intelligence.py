from server.matchmaking import ROLES, arrange, balance_metrics, weights
from server.match_intelligence import estimate
from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, auth, admin, register
from server.tests.test_results import setup_match, report

def players():
    return [{'id':str(i),'riot_id':str(i),'primary':ROLES[i%5],'secondary':ROLES[(i+1)%5],'rating':1600} for i in range(10)]

def test_role_estimate_shrinkage_and_unknown_role():
    cold=estimate(1600,[])
    assert all(v=={'estimate':1600,'sample':0,'uncertainty':200} for v in cold.values())
    history=[{'role':'MID','delta':24}]*15+[{'role':'BARON','delta':-24}]*15
    e=estimate(1600,history)
    assert e['MID']['estimate']>1600>e['BARON']['estimate']
    assert 0<e['MID']['estimate']-1600<=60
    assert e['MID']['uncertainty']<e['JUNGLE']['uncertainty']
    assert e['JUNGLE']==cold['JUNGLE']

def test_diversity_and_role_invariants():
    p=players()
    b,r,_=arrange(p)
    pairs={}
    for team in (b,r):
        for i,(a,_) in enumerate(team):
            for other,_ in team[i+1:]:
                pairs[tuple(sorted((a['id'],other['id'])))]=3
    before=balance_metrics(b,r,pairs)
    bb,rr,_=arrange(p,pairs)
    after=balance_metrics(bb,rr,pairs)
    assert after['repeated_pairs']<before['repeated_pairs']
    assert after['average_gap']==0
    assert after['secondary_count']==0
    assert len({a['id'] for a,_ in bb+rr})==10
    assert {role for _,role in bb}==set(ROLES)=={role for _,role in rr}
    assert arrange(p,pairs)==arrange(list(reversed(p)),pairs)
    assert weights(240)['secondary']<weights(0)['secondary']
    assert weights(240)['repeat']<weights(0)['repeat']
    assert weights(99999)==weights(240)

def test_private_snapshot_and_existing_elo_unchanged(tmp_path):
    with TestClient(create_app(str(tmp_path/'quality.db'),admin_key=ADMIN)) as c:
        mid,users,b,r,other=setup_match(c)
        first=c.get('/v1/queue/state',headers=auth(b)).json()['match']['quality']
        assert set(first)=={'algorithm','average_gap','secondary_count','repeated_pairs'}
        assert first['algorithm']=='role-aware-v3' and first['average_gap']==0
        outsider=register(c,2800)
        assert c.get('/v1/admin/match-quality',headers=auth(outsider)).status_code==403
        history=c.get('/v1/admin/match-quality',headers=admin()).json()['matches']
        snap=history[0]['quality']
        assert history[0]['id']==mid and snap['uncertainty_gap']==0
        report(c,mid,b,'BLUE')
        report(c,mid,r,'BLUE')
        assert c.get('/v1/me',headers=auth(b)).json()['rating_seed']==1612
        assert c.get('/v1/admin/match-quality',headers=admin()).json()['matches'][0]['quality']==snap
        stats=c.get('/v1/me/statistics',headers=auth(b)).json()
        assert stats['role_estimates']['BARON']['sample']==1
        assert stats['role_estimates']['MID']['sample']==0
        for u in users: assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        second=c.get('/v1/queue/state',headers=auth(b)).json()['match']['quality']
        assert second['repeated_pairs']<20
