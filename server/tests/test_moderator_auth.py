import hashlib
import pytest
from fastapi.testclient import TestClient
from server.app import create_app
from server.moderator_auth import ModeratorCredentials
from server.tests.test_api import ADMIN, register, auth, admin

def test_named_moderator_attribution_and_revocation(tmp_path):
    key='synthetic-moderator-key-not-for-production-123'
    headers={'X-Admin-Key':key}
    hashes={'moderator_a':hashlib.sha256(key.encode()).hexdigest()}
    path=str(tmp_path/'moderators.db')
    with TestClient(create_app(path,admin_key=ADMIN,moderator_hashes=hashes)) as c:
        user=register(c,4800)
        route=f"/v1/admin/accounts/{user['account']['id']}/reject"
        assert c.post(route,headers=auth(user)).status_code==403
        assert c.post(route,headers=headers).status_code==200
        audit=c.get('/v1/admin/operations',headers=admin()).json()['audit']
        assert audit[0]['actor']=='moderator_a'
        assert key not in str(audit)
    with TestClient(create_app(path,admin_key=ADMIN)) as c:
        assert c.get('/v1/admin/accounts',headers=headers).status_code==403
        assert c.get('/v1/admin/accounts',headers=admin()).status_code==200
        assert c.get('/v1/admin/operations',headers=admin()).json()['audit'][0]['actor']=='moderator_a'

def test_invalid_moderator_config():
    digest=hashlib.sha256(b'example').hexdigest()
    for config in ({'owner':digest},{'abc':'bad'},{'abc':digest,'def':digest}):
        with pytest.raises(ValueError): ModeratorCredentials(ADMIN,config)
