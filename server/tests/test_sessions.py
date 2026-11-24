from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, register, auth, admin, approve
import server.app as module

def test_session_lifecycle_and_queue_isolation(tmp_path, monkeypatch):
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    with TestClient(create_app(str(tmp_path/'sessions.db'),admin_key=ADMIN)) as c:
        data={'title':'Evening practice','region':'EUROPE','starts_at':stamp+60,'ends_at':stamp+3660,'capacity':10}
        assert c.post('/v1/admin/sessions',json=data).status_code==403
        created=c.post('/v1/admin/sessions',headers=admin(),json=data)
        assert created.status_code==201,created.text
        sid=created.json()['id']
        assert c.post('/v1/admin/sessions',headers=admin(),json=data).status_code==409
        roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
        users=[register(c,1100+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(11)]
        foreign=register(c,1200,region='ASIA')
        assert c.get('/v1/sessions',headers=auth(foreign)).json()=={'sessions':[]}
        assert c.post(f'/v1/sessions/{sid}/signup',headers=auth(users[0])).status_code==403
        for u in users:
            approve(c,u)
        for u in users[:10]:
            assert c.post(f'/v1/sessions/{sid}/signup',headers=auth(u)).status_code==200
        assert c.post(f'/v1/sessions/{sid}/signup',headers=auth(users[0])).json()['registered']==10
        assert c.post(f'/v1/sessions/{sid}/signup',headers=auth(users[10])).status_code==409
        url='/v1/queue/join?session_id='+sid
        assert c.post(url,headers=auth(users[0])).status_code==409
        monkeypatch.setattr(module,'now_ts',lambda:stamp+60)
        assert c.post(url,headers=auth(users[10])).status_code==403
        assert c.post('/v1/queue/join',headers=auth(users[10])).status_code==200
        for u in users[:9]:
            assert c.post(url,headers=auth(u)).status_code==200
        assert c.get('/v1/queue/state',headers=auth(users[0])).json()['match'] is None
        assert c.post('/v1/queue/join',headers=auth(users[0])).status_code==409
        assert c.post(url,headers=auth(users[9])).status_code==200
        m=c.get('/v1/queue/state',headers=auth(users[0])).json()['match']
        assert m['session_id']==sid
        assert c.get('/v1/queue/state',headers=auth(users[10])).json()['queued']
        assert c.post(f'/v1/sessions/{sid}/withdraw',headers=auth(users[0])).status_code==409
        assert c.post(f"/v1/matches/{m['id']}/decline",headers=auth(users[0])).status_code==200
        assert c.get('/v1/queue/state',headers=auth(users[1])).json()['session_id']==sid
        assert c.post(f'/v1/admin/sessions/{sid}/cancel',headers=admin()).status_code==200
        assert not c.get('/v1/queue/state',headers=auth(users[1])).json()['queued']
        assert c.get('/v1/queue/state',headers=auth(users[10])).json()['queued']

def test_withdraw_and_expiry(tmp_path,monkeypatch):
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    with TestClient(create_app(str(tmp_path/'expiry.db'),admin_key=ADMIN)) as c:
        a=register(c,1500);approve(c,a)
        data={'title':'Training','region':'EUROPE','starts_at':stamp+1,'ends_at':stamp+601,'capacity':10}
        sid=c.post('/v1/admin/sessions',headers=admin(),json=data).json()['id']
        path=f'/v1/sessions/{sid}'
        assert c.post(path+'/signup',headers=auth(a)).json()['enrolled']
        assert not c.post(path+'/withdraw',headers=auth(a)).json()['enrolled']
        assert c.post(path+'/signup',headers=auth(a)).status_code==200
        monkeypatch.setattr(module,'now_ts',lambda:stamp+1)
        assert c.post('/v1/queue/join?session_id='+sid,headers=auth(a)).status_code==200
        monkeypatch.setattr(module,'now_ts',lambda:stamp+601)
        assert not c.get('/v1/queue/state',headers=auth(a)).json()['queued']
        assert c.get('/v1/sessions',headers=auth(a)).json()=={'sessions':[]}

def test_cancel_session_keeps_assigned_match(tmp_path,monkeypatch):
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    with TestClient(create_app(str(tmp_path/'active.db'),admin_key=ADMIN)) as c:
        roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
        users=[register(c,1600+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(10)]
        data={'title':'Training','region':'EUROPE','starts_at':stamp+1,'ends_at':stamp+601,'capacity':10}
        sid=c.post('/v1/admin/sessions',headers=admin(),json=data).json()['id']
        for u in users:
            approve(c,u)
            assert c.post(f'/v1/sessions/{sid}/signup',headers=auth(u)).status_code==200
        monkeypatch.setattr(module,'now_ts',lambda:stamp+1)
        for u in users:
            assert c.post('/v1/queue/join?session_id='+sid,headers=auth(u)).status_code==200
        mid=c.get('/v1/queue/state',headers=auth(users[0])).json()['match']['id']
        assert c.post(f'/v1/admin/sessions/{sid}/cancel',headers=admin()).status_code==200
        for u in users:
            assert c.post(f'/v1/matches/{mid}/ready',headers=auth(u)).status_code==200
        assert c.get('/v1/queue/state',headers=auth(users[0])).json()['match']['status']=='ACTIVE'
