from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, auth, admin, register, profile, photo
from server.request_guard import JSON_LIMIT, UPLOAD_LIMIT

def test_registration_gate_preserves_accounts(tmp_path):
    path=str(tmp_path/'closed.db')
    with TestClient(create_app(path,admin_key=ADMIN)) as c:
        old=register(c,4100)
        code=c.post('/v1/me/recovery',headers=auth(old)).json()['code']
    with TestClient(create_app(path,admin_key=ADMIN,registration_open=False)) as c:
        assert c.get('/v1/public/config').json()['registration_open'] is False
        assert c.post('/v1/accounts',json=profile(4101)).status_code==403
        assert c.get('/v1/me',headers=auth(old)).status_code==200
        assert c.post('/v1/recover',json={'code':code}).status_code==200
        assert c.get('/openapi.json').status_code==404

def test_body_limits_and_no_secret_reflection(tmp_path):
    with TestClient(create_app(str(tmp_path/'body.db'),admin_key=ADMIN)) as c:
        assert c.post('/v1/accounts',content=b'x'*(JSON_LIMIT+1)).status_code==413
        def stream():
            yield b'x'*JSON_LIMIT
            yield b'x'
        r=c.post('/v1/accounts',content=stream(),headers={'Content-Type':'application/json'})
        assert r.status_code==413 and r.headers['cache-control']=='no-store'
        a=register(c,4102)
        assert c.post('/v1/me/evidence',headers=auth(a),files={'image':('ok.png',photo(1),'image/png')}).status_code==200
        assert c.post('/v1/me/evidence',headers=auth(a),files={'image':('large.png',b'x'*UPLOAD_LIMIT,'image/png')}).status_code==413
        assert c.post('/v1/me/evidence',headers=auth(a),files={'image':('invalid.png',b'bad','image/png')}).status_code==422
        secret='synthetic-secret-must-not-appear'
        r=c.post('/v1/recover',json={'code':secret})
        assert r.status_code==422 and secret not in r.text
        assert c.get('/v1/admin/accounts',headers={'X-Admin-Key':b'\xe9'}).status_code==403
        assert c.get('/v1/admin/accounts',headers=admin()).status_code==200
