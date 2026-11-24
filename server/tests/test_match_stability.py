from fastapi.testclient import TestClient
from server.app import create_app, Database, maintenance_tick, ACTIVE_TIMEOUT_SECONDS, DISPUTE_TIMEOUT_SECONDS
from server.matchmaking import ROLES, arrange, choose_match, balance_metrics, feasible_roster
from server.tests.test_api import ADMIN, auth, admin, register, approve
from server.tests.test_results import setup_match, report
import server.app as module

def test_full_queue_role_coverage_and_balance_cap():
    p=[{'id':str(i),'primary':'MID','secondary':'JUNGLE','rating':1600,'joined_at':i} for i in range(12)]
    for role in ('BARON','DRAGON','SUPPORT'):
        for _ in range(2):p.append({'id':str(len(p)),'primary':role,'secondary':'MID','rating':1600,'joined_at':len(p)})
    assert feasible_roster(p[:12]) is None
    (b,r,_),wait=choose_match(p,{},100)
    assert len({x['id'] for x,_ in b+r})==10
    assert {role for _,role in b}==set(ROLES)=={role for _,role in r}
    assert max(int(x['id']) for x,_ in b+r)>=12
    strong=[{'id':str(i),'primary':ROLES[i%5],'secondary':ROLES[(i+1)%5],'rating':1600 if i!=0 else 4000,'joined_at':i} for i in range(10)]
    assert arrange(strong) is None
    assert choose_match(strong,{},10000) is None
    strong.append({'id':'replacement','primary':'BARON','secondary':'JUNGLE','rating':1600,'joined_at':11})
    result=choose_match(strong,{},100)
    assert result is not None
    (b,r,_),_=result
    assert all(x['id']!='0' for x,_ in b+r)
    assert balance_metrics(b,r)['average_gap']<=120

def test_api_match_beyond_first_twelve(tmp_path,monkeypatch):
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    with TestClient(create_app(str(tmp_path/'coverage.db'),admin_key=ADMIN)) as c:
        roles=['MID']*12+['BARON']*2+['DRAGON']*2+['SUPPORT']*2
        users=[register(c,4300+i,role=role,secondary='JUNGLE' if role=='MID' else 'MID') for i,role in enumerate(roles)]
        for u in users:
            approve(c,u)
            assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        m=c.get('/v1/queue/state',headers=auth(users[-1])).json()['match']
        assert m and m['status']=='READY_CHECK'
        assert m['quality']['average_gap']==0
        assert len(m['blue']+m['red'])==10

def test_decline_immediate_rematch_and_no_double_assignment(tmp_path,monkeypatch):
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    path=str(tmp_path/'retry.db')
    with TestClient(create_app(path,admin_key=ADMIN)) as c:
        users=[register(c,4400+i,role=ROLES[i%5],secondary=ROLES[(i+1)%5]) for i in range(11)]
        for u in users:
            approve(c,u)
            assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        old=c.get('/v1/queue/state',headers=auth(users[0])).json()['match']['id']
        assert c.post(f'/v1/matches/{old}/decline',headers=auth(users[0])).status_code==200
        m=c.get('/v1/queue/state',headers=auth(users[-1])).json()['match']
        assert m and m['id']!=old
        assert c.get('/v1/queue/state',headers=auth(users[0])).json()['match'] is None
        maintenance_tick(Database(path))
        assert c.get('/v1/queue/state',headers=auth(users[-1])).json()['match']['id']==m['id']

def test_active_timeout_and_dispute_timeout_void_once(tmp_path,monkeypatch):
    clock=[module.now_ts()]
    monkeypatch.setattr(module,'now_ts',lambda:clock[0])
    path=str(tmp_path/'timeouts.db')
    with TestClient(create_app(path,admin_key=ADMIN)) as c:
        mid,users,b,r,other=setup_match(c)
        state=c.get('/v1/queue/state',headers=auth(b)).json()['match']
        assert state['phase_expires_at']==clock[0]+ACTIVE_TIMEOUT_SECONDS
        clock[0]+=ACTIVE_TIMEOUT_SECONDS-1
        maintenance_tick(Database(path))
        assert c.get('/v1/queue/state',headers=auth(b)).json()['match']['id']==mid
        clock[0]+=1
        assert report(c,mid,b,'BLUE').status_code==409
        for u in users:
            assert c.get('/v1/queue/state',headers=auth(u)).json()['match'] is None
            hist=c.get('/v1/me/results',headers=auth(u)).json()['results']
            assert len(hist)==1 and hist[0]['winner']=='VOID' and hist[0]['source']=='TIMEOUT'
            assert c.get('/v1/me',headers=auth(u)).json()['rating_seed']==1600
            assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        new=c.get('/v1/queue/state',headers=auth(b)).json()['match']
        mid2=new['id']
        lookup={u['account']['id']:u for u in users}
        for u in users:assert c.post(f'/v1/matches/{mid2}/ready',headers=auth(u)).status_code==200
        b2=lookup[next(p['account_id'] for p in new['blue'] if p['role']=='BARON')]
        r2=lookup[next(p['account_id'] for p in new['red'] if p['role']=='BARON')]
        assert report(c,mid2,b2,'BLUE').status_code==200
        assert report(c,mid2,r2,'RED').json()['phase_expires_at']==clock[0]+DISPUTE_TIMEOUT_SECONDS
        clock[0]+=DISPUTE_TIMEOUT_SECONDS
        maintenance_tick(Database(path))
        maintenance_tick(Database(path))
        assert c.post(f'/v1/admin/matches/{mid2}/resolve',headers=admin(),json={'winner':'BLUE'}).status_code==409
        hist=c.get('/v1/me/results',headers=auth(b2)).json()['results']
        assert len(hist)==2 and all(row['winner']=='VOID' and row['source']=='TIMEOUT' for row in hist)
