"""Concurrent ASGI load smoke using disposable synthetic data, not an external service."""
import hashlib
import json
import time
from concurrent.futures import ThreadPoolExecutor
from fastapi.testclient import TestClient
from server.app import create_app, Database
from server.tests.test_api import ADMIN, admin
from server.tests.test_batch_history import seed

def test_concurrent_queue_polling_and_paged_admin(tmp_path):
    path=str(tmp_path/'load.db')
    app=create_app(path,admin_key=ADMIN)
    stamp=int(time.time())
    with Database(path).transaction(write=True) as db:
        players=seed(db,n=240,rounds=36,stamp=stamp)
        # A large role-starved live queue cannot form a legitimate 5v5.
        for p in players[:100]:
            db.execute("UPDATE accounts SET primary_role='MID',secondary_role='JUNGLE' WHERE id=?",(p['id'],))
            db.execute('INSERT INTO queue VALUES(?,?,?)',(p['id'],'EUROPE',stamp))
            db.execute('INSERT INTO queue_presence VALUES(?,?)',(p['id'],stamp))
        for p in players[:12]:
            token='synthetic-ci-token-'+p['id']
            db.execute('UPDATE accounts SET token_hash=? WHERE id=?',(hashlib.sha256(token.encode()).hexdigest(),p['id']))
    with TestClient(app) as client:
        def worker(i):
            headers={'Authorization':'Bearer synthetic-ci-token-'+players[i]['id']}
            for _ in range(20):
                r=client.get('/v1/queue/state',headers=headers)
                assert r.status_code==200,r.text
                row=r.json()
                assert row['queued'] and row['waiting_in_region']==100 and row['match'] is None
            r=client.get('/v1/admin/accounts/page',headers=admin(),params={'limit':30,'offset':30*(i%8)})
            assert r.status_code==200,r.text
            assert r.json()['total']==240 and len(r.json()['items'])==30
        started=time.monotonic()
        with ThreadPoolExecutor(max_workers=12) as pool:
            list(pool.map(worker,range(12)))
        elapsed=time.monotonic()-started
        perf=client.get('/v1/admin/operations',headers=admin()).json()['performance']
        assert perf['groups']['queue']['samples']==240
        assert perf['groups']['queue']['errors']==0
        assert perf['groups']['admin']['samples']>=12
        print('LOAD_SMOKE '+json.dumps({'accounts':240,'history_matches':864,'waiting':100,'concurrency':12,'requests':252,'elapsed_seconds':round(elapsed,3),'performance':perf},sort_keys=True))
