from fastapi.testclient import TestClient
from server.app import create_app,Database,maintenance_tick,ACTIVE_TIMEOUT_SECONDS,IMAGE_DECODERS
from server.tests.test_api import ADMIN,admin,auth,register,photo
from server.tests.test_results import setup_match
import server.app as module

def test_strict_review_and_revision_encoding(tmp_path):
    with TestClient(create_app(str(tmp_path/'review.db'),admin_key=ADMIN,strict_review=True)) as c:
        u=register(c,5100)
        base='/v1/admin/accounts/'+u['account']['id']
        assert c.post(base+'/approve',headers=admin()).status_code==428
        before=c.get(base,headers=admin()).json()['review_revision']
        for i in (1,2):
            assert c.post('/v1/me/evidence',headers=auth(u),files={'image':('proof.png',photo(i),'image/png')}).status_code==200
        assert c.post(base+'/approve',headers={**admin(),'X-Review-Revision':before}).status_code==409
        assert c.post(base+'/approve',headers={**admin(),'X-Review-Revision':b'\xe9'*64}).status_code==409
        after=c.get(base,headers=admin()).json()['review_revision']
        assert c.post(base+'/approve',headers={**admin(),'X-Review-Revision':after}).status_code==200
        assert c.post(base+'/reject',headers=admin()).status_code==428

def test_expired_lobby_and_proof_guard(tmp_path,monkeypatch):
    clock=[module.now_ts()]
    monkeypatch.setattr(module,'now_ts',lambda:clock[0])
    with TestClient(create_app(str(tmp_path/'deadline.db'),admin_key=ADMIN)) as c:
        mid,_,blue,red,_=setup_match(c)
        base='/v1/matches/'+mid
        assert c.put(base+'/lobby',headers=auth(blue),json={'room':'Room CI'}).status_code==200
        clock[0]+=ACTIVE_TIMEOUT_SECONDS
        assert c.post(base+'/lobby/arrive',headers=auth(red)).status_code==409
        assert c.put(base+'/lobby',headers=auth(blue),json={'room':'Other room'}).status_code==409
        assert c.post(base+'/evidence',headers=auth(blue),files={'image':('proof.png',photo(1),'image/png')}).status_code==409
        assert c.get('/v1/queue/state',headers=auth(blue)).json()['match'] is None

def test_maintenance_retains_hash_and_prunes_expired_bytes(tmp_path,monkeypatch):
    clock=[module.now_ts()]
    monkeypatch.setattr(module,'now_ts',lambda:clock[0])
    path=str(tmp_path/'retention.db')
    with TestClient(create_app(path,admin_key=ADMIN)) as c:
        mid,_,blue,_,_=setup_match(c)
        assert c.post(f'/v1/matches/{mid}/evidence',headers=auth(blue),files={'image':('proof.png',photo(1),'image/png')}).status_code==200
        assert c.post(f'/v1/admin/matches/{mid}/close',headers=admin()).status_code==200
        store=Database(path)
        with store.transaction(write=True) as db:
            old=db.execute('SELECT sha256 FROM result_evidence WHERE match_id=?',(mid,)).fetchone()[0]
            db.execute('DELETE FROM result_proof_history WHERE match_id=?',(mid,))
        clock[0]+=31*86400
        maintenance_tick(store)
        with store.transaction() as db:
            assert db.execute('SELECT count(*) FROM result_evidence WHERE match_id=?',(mid,)).fetchone()[0]==0
            assert db.execute('SELECT sha256 FROM result_proof_history WHERE match_id=?',(mid,)).fetchone()[0]==old
        maintenance_tick(store)

def test_image_decoder_capacity_recovers(tmp_path):
    with TestClient(create_app(str(tmp_path/'capacity.db'),admin_key=ADMIN)) as c:
        u=register(c,5101)
        args={'headers':auth(u),'files':{'image':('proof.png',photo(1),'image/png')}}
        assert IMAGE_DECODERS.acquire(False)
        assert IMAGE_DECODERS.acquire(False)
        try:
            assert c.post('/v1/me/evidence',**args).status_code==503
        finally:
            IMAGE_DECODERS.release();IMAGE_DECODERS.release()
        assert c.post('/v1/me/evidence',**args).status_code==200
