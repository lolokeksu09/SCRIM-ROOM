from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, register, auth, admin, photo

def test_admin_panel_security_and_no_secrets(tmp_path):
    with TestClient(create_app(str(tmp_path/'admin.db'), admin_key=ADMIN)) as c:
        for path in ('/admin','/admin/style.css','/admin/app.js'):
            r=c.get(path)
            assert r.status_code == 200
            assert r.headers['cache-control']=='no-store'
            assert "frame-ancestors 'none'" in r.headers['content-security-policy']
            assert ADMIN not in r.text
        a=register(c, 81)
        ident=a['account']['id']
        for path in ('/v1/admin/accounts','/v1/admin/matches',f'/v1/admin/accounts/{ident}/evidence/1'):
            r=c.get(path)
            assert r.status_code==403 and r.headers['cache-control']=='no-store'
        assert c.get('/v1/admin/accounts',headers=auth(a)).status_code==403
        assert c.get('/v1/admin/matches',headers=admin()).json()==[]

def test_review_revision_prevents_stale_approval(tmp_path):
    with TestClient(create_app(str(tmp_path/'review.db'), admin_key=ADMIN)) as c:
        a=register(c,82)
        ident=a['account']['id']
        first=c.get('/v1/admin/accounts',headers=admin()).json()[0]
        assert 'token_hash' not in first and 'token' not in first
        for i in (1,2):
            assert c.post('/v1/me/evidence',headers=auth(a),files={'image':(f'{i}.png',photo(i),'image/png')}).status_code==200
        path=f'/v1/admin/accounts/{ident}/approve'
        assert c.post(path,headers={**admin(),'X-Review-Revision':first['review_revision']}).status_code==409
        review=c.get('/v1/admin/accounts',headers=admin()).json()[0]
        assert review['review_revision']!=first['review_revision']
        assert c.post(path,headers={**admin(),'X-Review-Revision':review['review_revision']}).status_code==200
        assert c.get('/v1/me',headers=auth(a)).json()['status']=='APPROVED'
        assert c.post(f'/v1/admin/accounts/{ident}/reject',headers={**admin(),'X-Review-Revision':review['review_revision']}).status_code==409
