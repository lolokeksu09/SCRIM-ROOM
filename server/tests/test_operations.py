import sqlite3
from fastapi.testclient import TestClient
from server.app import create_app
from server.backup import backup
from server.tests.test_api import ADMIN, register, auth, admin, approve
import pytest

def test_recovery_rotation_and_replay(tmp_path):
    with TestClient(create_app(str(tmp_path/'recovery.db'),admin_key=ADMIN)) as c:
        a=register(c,3400); approve(c,a)
        code1=c.post('/v1/me/recovery',headers=auth(a)).json()['code']
        code2=c.post('/v1/me/recovery',headers=auth(a)).json()['code']
        assert code1!=code2 and len(code2)==43
        assert c.post('/v1/recover',json={'code':code1}).status_code==401
        r=c.post('/v1/recover',json={'code':code2})
        assert r.status_code==200,r.text
        recovered=r.json()
        assert recovered['account']['id']==a['account']['id']
        assert recovered['account']['status']=='APPROVED'
        assert c.get('/v1/me',headers=auth(a)).status_code==401
        assert c.get('/v1/me',headers=auth(recovered)).status_code==200
        assert c.post('/v1/recover',json={'code':code2}).status_code==401
        assert r.headers['cache-control']=='no-store'

def test_privacy_inbox_and_audit(tmp_path):
    with TestClient(create_app(str(tmp_path/'privacy.db'),admin_key=ADMIN)) as c:
        a,b=register(c,3401),register(c,3402)
        for x in (a,b): approve(c,x)
        aid,bid=a['account']['id'],b['account']['id']
        assert c.put('/v1/me/privacy',headers=auth(b),json={'discoverable':False}).status_code==200
        assert c.get('/v1/community/find',headers=auth(a),params={'riot_id':b['account']['riot_id']}).status_code==404
        assert c.post('/v1/community/friends/request',headers=auth(a),json={'account_id':bid}).status_code==404
        assert c.put('/v1/me/privacy',headers=auth(b),json={'discoverable':True}).status_code==200
        assert c.post('/v1/community/friends/request',headers=auth(a),json={'account_id':bid}).status_code==200
        items=c.get('/v1/me/inbox',headers=auth(b)).json()
        event=next(x for x in items['items'] if x['id'].startswith('friend:'))
        assert not event['read']
        c.post('/v1/me/inbox/read',headers=auth(a),json={'ids':[event['id']]})
        assert not next(x for x in c.get('/v1/me/inbox',headers=auth(b)).json()['items'] if x['id']==event['id'])['read']
        assert c.post('/v1/me/inbox/read',headers=auth(b),json={'ids':[event['id']]}).status_code==200
        assert next(x for x in c.get('/v1/me/inbox',headers=auth(b)).json()['items'] if x['id']==event['id'])['read']
        assert c.get('/readyz').json()=={'status':'ready'}
        assert c.get('/v1/admin/operations',headers=auth(a)).status_code==403
        audit=c.get('/v1/admin/operations',headers=admin()).json()
        assert audit['counts']['accounts']==2
        assert len(audit['audit'])==2
        assert all(x['status']==200 for x in audit['audit'])
        assert ADMIN not in str(audit)

def test_consistent_backup_and_restore(tmp_path):
    source=tmp_path/'source.db'
    with TestClient(create_app(str(source),admin_key=ADMIN)) as c:
        a=register(c,3403)
        dest=backup(source,tmp_path/'private'/'copy.db')
        assert dest.stat().st_mode & 0o777 == 0o600
        with pytest.raises(FileExistsError): backup(source,dest)
        with sqlite3.connect(dest) as db: assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        with TestClient(create_app(str(dest),admin_key=ADMIN)) as restored:
            assert restored.get('/v1/me',headers=auth(a)).json()['id']==a['account']['id']
