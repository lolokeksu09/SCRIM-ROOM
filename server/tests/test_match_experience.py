from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, auth, admin, register, photo
from server.tests.test_results import setup_match, report

def test_lobby_access_arrival_and_lock(tmp_path):
    with TestClient(create_app(str(tmp_path/'lobby.db'),admin_key=ADMIN)) as c:
        mid,users,b,r,other=setup_match(c)
        outsider=register(c,2500)
        path=f'/v1/matches/{mid}/lobby'
        assert c.post(path+'/arrive',headers=auth(other)).status_code==409
        assert c.put(path,headers=auth(outsider),json={'room':'Practice A'}).status_code==404
        assert c.put(path,headers=auth(r),json={'room':'Practice A'}).status_code==403
        assert c.put(path,headers=auth(b),json={'room':'Practice A'}).status_code==200
        assert c.get('/v1/queue/state',headers=auth(other)).json()['match']['lobby_room']=='Practice A'
        assert c.get('/v1/queue/state',headers=auth(outsider)).json()['match'] is None
        assert c.post(path+'/arrive',headers=auth(other)).json()['arrived']==1
        assert c.post(path+'/arrive',headers=auth(other)).json()['arrived']==1
        assert c.put(path,headers=auth(b),json={'room':'Practice B'}).status_code==409
        for u in users: assert c.post(path+'/arrive',headers=auth(u)).status_code==200
        assert c.get('/v1/queue/state',headers=auth(b)).json()['match']['lobby_arrivals']==10

def test_proof_access_immutability_and_role_statistics(tmp_path):
    with TestClient(create_app(str(tmp_path/'proof.db'),admin_key=ADMIN)) as c:
        mid,users,b,r,other=setup_match(c)
        path=f'/v1/matches/{mid}/evidence'
        def upload(u,raw): return c.post(path,headers=auth(u),files={'image':('result.png',raw,'image/png')})
        assert upload(other,photo(1)).status_code==403
        assert upload(b,b'invalid').status_code==422
        assert upload(b,photo(1)).status_code==200
        assert upload(b,photo(1)).status_code==200
        assert upload(b,photo(2)).status_code==409
        review=f'/v1/admin/matches/{mid}/evidence/BLUE'
        assert c.get(review,headers=auth(r)).status_code==403
        assert c.get(review,headers=admin()).headers['content-type']=='image/jpeg'
        assert c.get('/v1/queue/state',headers=auth(b)).json()['match']['own_evidence']
        report(c,mid,b,'BLUE')
        report(c,mid,r,'RED')
        assert upload(r,photo(2)).status_code==200
        assert c.post(f'/v1/admin/matches/{mid}/resolve',headers=admin(),json={'winner':'BLUE'}).status_code==200
        assert upload(r,photo(2)).status_code==409
        stats=c.get('/v1/me/statistics',headers=auth(b)).json()
        assert stats['wins']==1 and stats['played']==1 and stats['win_rate']==100
        assert stats['roles']['BARON']=={'played':1,'wins':1,'losses':0}
        assert stats['calibration']=={'completed':1,'target':10,'provisional':True}
        assert c.get('/v1/me/statistics',headers=auth(r)).json()['losses']==1
        assert c.get('/v1/me/statistics').status_code==401

def test_void_not_counted_for_calibration(tmp_path):
    with TestClient(create_app(str(tmp_path/'void.db'),admin_key=ADMIN)) as c:
        mid,users,b,r,other=setup_match(c)
        assert c.post(f'/v1/admin/matches/{mid}/close',headers=admin()).status_code==200
        stats=c.get('/v1/me/statistics',headers=auth(b)).json()
        assert stats['voids']==1 and stats['played']==0 and stats['win_rate'] is None
        assert stats['calibration']['completed']==0


def test_ready_deadline_is_inclusive_and_persists_cancellation(tmp_path,monkeypatch):
    import server.app as module
    from server.tests.test_api import approve
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    with TestClient(create_app(str(tmp_path/'deadline.db'),admin_key=ADMIN)) as c:
        roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
        users=[register(c,2600+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(10)]
        for u in users:
            approve(c,u)
            assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        m=c.get('/v1/queue/state',headers=auth(users[0])).json()['match']
        assert m['server_now']==stamp and not m['own_ready']
        url=f"/v1/matches/{m['id']}/ready"
        assert c.post(url,headers=auth(users[0])).json()['own_ready']
        monkeypatch.setattr(module,'now_ts',lambda:m['deadline'])
        assert c.post(url,headers=auth(users[1])).status_code==409
        state=c.get('/v1/queue/state',headers=auth(users[1])).json()
        assert state['match'] is None and state['discipline']['incidents']==1
        assert c.get('/v1/queue/state',headers=auth(users[0])).json()['queued']
