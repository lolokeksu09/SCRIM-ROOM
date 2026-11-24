from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, auth, admin, photo
from server.tests.test_results import setup_match, report

def upload(c,mid,user,image):
    return c.post(f'/v1/matches/{mid}/evidence',headers=auth(user),files={'image':('result.png',image,'image/png')})

def test_matching_reports_without_evidence_require_review(tmp_path):
    with TestClient(create_app(str(tmp_path/'missing.db'),admin_key=ADMIN,strict_results=True)) as c:
        mid,_,b,r,_=setup_match(c)
        report(c,mid,b,'BLUE')
        result=report(c,mid,r,'BLUE').json()
        assert result['status']=='DISPUTED' and result['review_reason']=='MISSING_EVIDENCE'
        assert c.get('/v1/me',headers=auth(b)).json()['rating_seed']==1600
        path=f'/v1/admin/matches/{mid}/resolve'
        assert c.post(path,headers=admin(),json={'winner':'BLUE'}).status_code==422
        assert c.post(path,headers=admin(),json={'winner':'BLUE','note':'Reviewed match evidence manually'}).status_code==200

def test_duplicate_proof_requires_review(tmp_path):
    with TestClient(create_app(str(tmp_path/'duplicate.db'),admin_key=ADMIN,strict_results=True)) as c:
        mid,_,b,r,_=setup_match(c)
        assert upload(c,mid,b,photo(1)).status_code==200
        assert upload(c,mid,r,photo(1)).status_code==200
        report(c,mid,b,'BLUE')
        result=report(c,mid,r,'BLUE').json()
        assert result['status']=='DISPUTED' and result['review_reason']=='DUPLICATE_EVIDENCE'

def test_distinct_proof_consensus_and_cross_match_reuse(tmp_path):
    with TestClient(create_app(str(tmp_path/'distinct.db'),admin_key=ADMIN,strict_results=True)) as c:
        mid,users,b,r,_=setup_match(c)
        assert upload(c,mid,b,photo(1)).status_code==200
        assert upload(c,mid,r,photo(2)).status_code==200
        report(c,mid,b,'BLUE')
        assert report(c,mid,r,'BLUE').json()['status']=='CLOSED'
        for u in users: assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        m=c.get('/v1/queue/state',headers=auth(users[0])).json()['match']
        lookup={u['account']['id']:u for u in users}
        for u in users: assert c.post(f"/v1/matches/{m['id']}/ready",headers=auth(u)).status_code==200
        b=lookup[next(p['account_id'] for p in m['blue'] if p['role']=='BARON')]
        r=lookup[next(p['account_id'] for p in m['red'] if p['role']=='BARON')]
        assert upload(c,m['id'],b,photo(1)).status_code==200
        assert upload(c,m['id'],r,photo(3)).status_code==200
        report(c,m['id'],b,'BLUE')
        result=report(c,m['id'],r,'BLUE').json()
        assert result['status']=='DISPUTED' and result['review_reason']=='REUSED_EVIDENCE'
