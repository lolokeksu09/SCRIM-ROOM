from fastapi.testclient import TestClient
from server.app import create_app,Database
from server.tests.test_api import ADMIN, admin
from server.tests.test_batch_history import seed
from server.performance import PerformanceWindow

def test_pagination_bounds_search_and_private_details(tmp_path):
    path=str(tmp_path/'page.db')
    app=create_app(path,admin_key=ADMIN)
    with Database(path).transaction(write=True) as db:
        players=seed(db,n=120,rounds=0)
        db.execute("UPDATE accounts SET status='PENDING_REVIEW' WHERE id LIKE 'p00%'")
    with TestClient(app) as c:
        base='/v1/admin/accounts/page'
        assert c.get(base).status_code==403
        first=c.get(base,headers=admin(),params={'limit':30}).json()
        second=c.get(base,headers=admin(),params={'limit':30,'offset':30}).json()
        assert first['total']==second['total']==120
        assert len(first['items'])==len(second['items'])==30
        assert not ({x['id'] for x in first['items']}&{x['id'] for x in second['items']})
        assert first['counts']=={'PENDING_REVIEW':100,'APPROVED':20}
        search=c.get(base,headers=admin(),params={'q':'p0119','status':'APPROVED'}).json()
        assert search['total']==1 and search['items'][0]['id']=='p0119'
        assert c.get(base,headers=admin(),params={'limit':101}).status_code==422
        detail='/v1/admin/accounts/p0119'
        assert c.get(detail).status_code==403
        assert c.get(detail,headers=admin()).json()['id']=='p0119'
        assert 'token_hash' not in str(first)
        assert len(c.get('/v1/admin/accounts',headers=admin()).json())==100
        assert c.get('/v1/admin/operations',headers=admin()).json()['performance']['groups']['admin']['samples']>0

def test_performance_window_bound_and_no_identifiers():
    p=PerformanceWindow(capacity=4)
    for i in range(10):p.record('/v1/matches/secret-id',500 if i==9 else 200,i/1000)
    snap=p.snapshot()
    assert snap['groups']['match']=={'samples':4,'p50_ms':7,'p95_ms':9,'max_ms':9,'errors':1}
    assert 'secret-id' not in str(snap)
