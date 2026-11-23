import io
import os
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from server.app import create_app

ADMIN = 'test-only-admin-key-32-character-minimum-1234'


def profile(i, role='JUNGLE', secondary='MID', region='EUROPE', rank='GRANDMASTER'):
    return {
        'riot_id': f'Pilot{i}#EUW', 'region': region,
        'current_rank': rank, 'previous_rank': 'MASTER',
        'primary': role, 'secondary': secondary,
    }


def photo(slot=1):
    image = Image.new('RGB', (12, 12), (slot * 40, slot * 50, slot * 60))
    out = io.BytesIO()
    image.save(out, format='PNG')
    return out.getvalue()


@pytest.fixture
def client(tmp_path):
    app = create_app(str(tmp_path/'pilot.db'), admin_key=ADMIN)
    with TestClient(app) as client:
        yield client


def register(client, i, **kwargs):
    p = profile(i, **kwargs)
    r = client.post('/v1/accounts', json=p)
    assert r.status_code == 201, r.text
    return r.json()


def auth(r):
    return {'Authorization': 'Bearer ' + r['token']}


def admin():
    return {'X-Admin-Key': ADMIN}


def approve(client, r):
    for slot in (1, 2):
        res = client.post('/v1/me/evidence', headers=auth(r), files={'image': (f'{slot}.png', photo(slot), 'image/png')})
        assert res.status_code == 200, res.text
    res = client.post(f"/v1/admin/accounts/{r['account']['id']}/approve", headers=admin())
    assert res.status_code == 200, res.text


def test_health_and_auth(client):
    assert client.get('/healthz').json() == {'status': 'ok'}
    assert client.get('/v1/me').status_code == 401
    assert client.get('/v1/admin/accounts').status_code == 403


def test_registration_policy_and_duplicates(client):
    denied = client.post('/v1/accounts', json=profile(1, rank='MASTER'))
    assert denied.status_code == 422
    bad_role = client.post('/v1/accounts', json=profile(2, role='MID', secondary='MID'))
    assert bad_role.status_code == 422
    r = register(client, 3)
    assert r['account']['status'] == 'PENDING_REVIEW'
    assert client.post('/v1/accounts', json=profile(3)).status_code == 409
    assert client.get('/v1/me', headers=auth(r)).json()['riot_id'] == 'Pilot3#EUW'


def test_evidence_and_manual_approval(client):
    r = register(client, 10)
    assert client.post('/v1/queue/join', headers=auth(r)).status_code == 403
    assert client.post(f"/v1/admin/accounts/{r['account']['id']}/approve", headers=admin()).status_code == 409
    assert client.post('/v1/me/evidence', headers=auth(r), files={'image': ('bad.txt', b'fake', 'image/png')}).status_code == 422
    approve(client, r)
    assert client.get('/v1/me', headers=auth(r)).json()['status'] == 'APPROVED'
    evid = client.get(f"/v1/admin/accounts/{r['account']['id']}/evidence/1", headers=admin())
    assert evid.status_code == 200 and evid.headers['content-type'] == 'image/jpeg'
    assert client.post('/v1/queue/join', headers=auth(r)).status_code == 200
    assert client.post('/v1/queue/join', headers=auth(r)).status_code == 200
    assert client.get('/v1/queue/state', headers=auth(r)).json()['queued']
    assert client.post('/v1/queue/leave', headers=auth(r)).status_code == 200
    assert not client.get('/v1/queue/state', headers=auth(r)).json()['queued']


def test_reprofile_resets_approval_and_evidence(client):
    r = register(client, 20)
    approve(client, r)
    changed = client.put('/v1/me', headers=auth(r), json=profile(20, rank='CHALLENGER'))
    assert changed.status_code == 200
    assert changed.json()['status'] == 'PENDING_REVIEW'
    assert changed.json()['evidence_count'] == 0
    assert client.post('/v1/queue/join', headers=auth(r)).status_code == 403


def test_matchmaking_and_readiness(client):
    # All players prefer the exact roles so each team gets one per position.
    roles = ['BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT']
    accounts = []
    for i in range(10):
        role = roles[i % 5]
        second = roles[(i + 1) % 5]
        acc = register(client, 100+i, role=role, secondary=second)
        approve(client, acc)
        accounts.append(acc)
    for acc in accounts:
        assert client.post('/v1/queue/join', headers=auth(acc)).status_code == 200
    state = client.get('/v1/queue/state', headers=auth(accounts[0])).json()
    match = state['match']
    assert match is not None and match['status'] == 'READY_CHECK'
    assert len(match['blue']) == len(match['red']) == 5
    assert {p['role'] for p in match['blue']} == set(roles)
    assert {p['role'] for p in match['red']} == set(roles)
    assert len({p['account_id'] for p in match['blue'] + match['red']}) == 10
    for acc in accounts:
        res = client.post(f"/v1/matches/{match['id']}/ready", headers=auth(acc))
        assert res.status_code == 200
    state = client.get('/v1/queue/state', headers=auth(accounts[0])).json()
    assert state['match']['status'] == 'ACTIVE'
    assert state['match']['ready_count'] == 10


def test_no_cross_region_match(client):
    roles = ['BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT']
    accounts = []
    for i in range(10):
        role = roles[i % 5]
        second = roles[(i + 1) % 5]
        acc = register(client, 200+i, role=role, secondary=second, region='EUROPE' if i < 5 else 'ASIA')
        approve(client, acc)
        client.post('/v1/queue/join', headers=auth(acc))
        accounts.append(acc)
    for acc in accounts:
        state = client.get('/v1/queue/state', headers=auth(acc)).json()
        assert state['match'] is None
        assert state['queued']


def test_tokens_private_and_decline(client):
    roles = ['BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT']
    users = []
    for i in range(10):
        role = roles[i % 5]
        acc = register(client, 300+i, role=role, secondary=roles[(i+1)%5])
        approve(client, acc)
        users.append(acc)
        client.post('/v1/queue/join', headers=auth(acc))
    match_id = client.get('/v1/queue/state', headers=auth(users[0])).json()['match']['id']
    res = client.post(f'/v1/matches/{match_id}/decline', headers=auth(users[0]))
    assert res.status_code == 200
    assert client.get('/v1/queue/state', headers=auth(users[0])).json()['match'] is None
    assert not client.get('/v1/queue/state', headers=auth(users[0])).json()['queued']
    # Others were requeued, but only nine remain, so no immediate rematch.
    assert client.get('/v1/queue/state', headers=auth(users[1])).json()['queued']


def test_stale_readiness_expires(client, monkeypatch):
    roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
    users=[]
    for i in range(10):
        a=register(client, 400+i, role=roles[i%5], secondary=roles[(i+1)%5]); approve(client,a)
        users.append(a); client.post('/v1/queue/join',headers=auth(a))
    mid=client.get('/v1/queue/state', headers=auth(users[0])).json()['match']['id']
    import server.app as module
    base = module.now_ts()
    monkeypatch.setattr(module, 'now_ts', lambda: base + 185)
    state = client.get('/v1/queue/state', headers=auth(users[0])).json()
    assert state['match'] is None
    assert not state['queued']


def test_no_public_ranking_endpoint(client):
    assert client.get('/v1/leaderboard').status_code == 404


def test_match_skips_incompatible_earliest_player(client):
    # The earliest player duplicates a role; the eleventh allows 5x5 allocation.
    roles = ['BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT']
    accounts = []
    for i in range(11):
        role = 'BARON' if i == 0 else roles[(i - 1) % 5]
        alt = 'JUNGLE' if role == 'BARON' else 'BARON'
        a = register(client, 500+i, role=role, secondary=alt)
        approve(client, a)
        accounts.append(a)
        client.post('/v1/queue/join', headers=auth(a))
    match = client.get('/v1/queue/state', headers=auth(accounts[10])).json()['match']
    assert match is not None
    assert len(match['blue'] + match['red']) == 10
    assert {p['role'] for p in match['blue']} == set(roles)
