from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, register, auth, admin, approve

def setup_match(c):
    roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
    accounts=[register(c,700+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(10)]
    lookup={a['account']['id']:a for a in accounts}
    for a in accounts:
        approve(c,a)
        assert c.post('/v1/queue/join',headers=auth(a)).status_code==200
    m=c.get('/v1/queue/state',headers=auth(accounts[0])).json()['match']
    mid=m['id']
    for a in accounts:
        assert c.post(f'/v1/matches/{mid}/ready',headers=auth(a)).status_code==200
    blue=lookup[next(p['account_id'] for p in m['blue'] if p['role']=='BARON')]
    red=lookup[next(p['account_id'] for p in m['red'] if p['role']=='BARON')]
    other=next(a for a in accounts if a not in (blue,red))
    return mid,accounts,blue,red,other

def report(c,mid,a,winner):
    return c.post(f'/v1/matches/{mid}/result',headers=auth(a),json={'winner':winner})

def test_consensus_exactly_once_and_private_history(tmp_path):
    with TestClient(create_app(str(tmp_path/'consensus.db'),admin_key=ADMIN)) as c:
        mid,accounts,b,r,other=setup_match(c)
        outsider=register(c,999)
        assert report(c,mid,outsider,'BLUE').status_code==404
        assert report(c,mid,other,'BLUE').status_code==403
        assert report(c,mid,b,'BLUE').json()['status']=='ACTIVE'
        assert c.get('/v1/me',headers=auth(b)).json()['rating_seed']==1600
        assert report(c,mid,b,'RED').status_code==409
        assert report(c,mid,r,'BLUE').json()['status']=='CLOSED'
        assert report(c,mid,r,'BLUE').json()['status']=='CLOSED'
        assert c.get('/v1/me',headers=auth(b)).json()['rating_seed']==1612
        assert c.get('/v1/me',headers=auth(r)).json()['rating_seed']==1588
        assert c.get('/v1/me/results',headers=auth(outsider)).json()=={'results':[]}
        history=c.get('/v1/me/results',headers=auth(b)).json()['results']
        assert len(history)==1 and history[0]['after_rating']-history[0]['before_rating']==12
        assert c.get('/v1/queue/state',headers=auth(b)).json()['match'] is None
        assert c.post('/v1/queue/join',headers=auth(b)).status_code==200

def test_dispute_resolution_and_void_no_change(tmp_path):
    with TestClient(create_app(str(tmp_path/'dispute.db'),admin_key=ADMIN)) as c:
        mid,accounts,b,r,other=setup_match(c)
        assert report(c,mid,b,'BLUE').status_code==200
        assert report(c,mid,r,'RED').json()['status']=='DISPUTED'
        assert c.post('/v1/queue/join',headers=auth(b)).status_code==409
        path=f'/v1/admin/matches/{mid}/resolve'
        assert c.post(path,json={'winner':'VOID'},headers=auth(b)).status_code==403
        assert c.post(path,json={'winner':'VOID'},headers=admin()).json()['status']=='CLOSED'
        assert c.post(path,json={'winner':'BLUE'},headers=admin()).status_code==409
        for a in accounts:
            assert c.get('/v1/me',headers=auth(a)).json()['rating_seed']==1600
            hist=c.get('/v1/me/results',headers=auth(a)).json()['results']
            assert hist[0]['winner']=='VOID' and hist[0]['after_rating'] is None

def test_moderator_can_resolve_winner(tmp_path):
    with TestClient(create_app(str(tmp_path/'resolved.db'),admin_key=ADMIN)) as c:
        mid,accounts,b,r,other=setup_match(c)
        report(c,mid,b,'BLUE')
        report(c,mid,r,'RED')
        resolved=c.post(f'/v1/admin/matches/{mid}/resolve',headers=admin(),json={'winner':'RED'})
        assert resolved.status_code==200 and resolved.json()['result']['source']=='MODERATOR'
        assert c.get('/v1/me',headers=auth(r)).json()['rating_seed']==1612
        assert c.get('/v1/me',headers=auth(b)).json()['rating_seed']==1588
