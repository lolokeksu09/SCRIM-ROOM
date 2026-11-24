from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, auth, register, approve
import server.app as module

def test_idle_queue_expires_without_penalty_or_automatic_rejoin(tmp_path,monkeypatch):
    stamp=module.now_ts()
    clock=[stamp]
    monkeypatch.setattr(module,'now_ts',lambda:clock[0])
    with TestClient(create_app(str(tmp_path/'presence.db'),admin_key=ADMIN)) as c:
        a,b=register(c,3700),register(c,3701)
        for u in (a,b):
            approve(c,u)
            assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        clock[0]+=60
        assert c.get('/v1/queue/state',headers=auth(a)).json()['queued']
        clock[0]+=30
        state=c.get('/v1/queue/state',headers=auth(b)).json()
        assert not state['queued']
        assert state['discipline']['incidents']==0
        assert state['waiting_in_region']==1
        assert c.get('/v1/queue/state',headers=auth(a)).json()['queued']
        assert c.post('/v1/queue/join',headers=auth(b)).status_code==200

def test_stale_tenth_player_not_matched(tmp_path,monkeypatch):
    clock=[module.now_ts()]
    monkeypatch.setattr(module,'now_ts',lambda:clock[0])
    roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
    with TestClient(create_app(str(tmp_path/'match.db'),admin_key=ADMIN)) as c:
        users=[register(c,3800+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(10)]
        for u in users: approve(c,u)
        assert c.post('/v1/queue/join',headers=auth(users[0])).status_code==200
        clock[0]+=90
        for u in users[1:]: assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        state=c.get('/v1/queue/state',headers=auth(users[1])).json()
        assert state['queued'] and state['waiting_in_region']==9 and state['match'] is None
        assert not c.get('/v1/queue/state',headers=auth(users[0])).json()['queued']
        assert c.post('/v1/queue/join',headers=auth(users[0])).json()['match']['status']=='READY_CHECK'
