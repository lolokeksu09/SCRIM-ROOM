import io
import shutil
from PIL import Image, ImageDraw, ImageFont
from fastapi.testclient import TestClient
from server.app import create_app
from server.ocr import parse_stats, recognize_stats
from server.tests.test_api import ADMIN, register, auth, admin, approve, photo, profile

def test_parser_ambiguous_and_missing():
    assert parse_stats('Matches: 240\nWin rate: 58.3%\nKDA: 5.2\nGrandmaster') == {'matches':240,'win_rate':58.3,'kda':5.2,'rank_candidates':['GRANDMASTER']}
    assert parse_stats('Матчи: 45\nМатчи: 46\nВинрейт: 101%\nКДА: 2/3/4')['matches'] is None
    assert parse_stats('КДА: 2/3/4')['kda'] is None
    assert parse_stats('') == {'matches':None,'win_rate':None,'kda':None,'rank_candidates':[]}

def test_real_ocr_and_owner_boundary(tmp_path):
    assert shutil.which('tesseract'), 'CI must install OCR engine'
    image=Image.new('RGB',(1200,500),'white')
    draw=ImageDraw.Draw(image)
    font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',56)
    for i,line in enumerate(['Matches: 240','Win rate: 58.3%','KDA: 5.2','Grandmaster']):
        draw.text((50,30+i*100),line,font=font,fill='black')
    buf=io.BytesIO()
    image.save(buf,format='PNG')
    raw=buf.getvalue()
    with TestClient(create_app(str(tmp_path/'ocr.db'),admin_key=ADMIN)) as c:
        a,b=register(c,501),register(c,502)
        assert c.post('/v1/me/evidence',headers=auth(a),files={'image':('stats.png',raw,'image/png')}).status_code==200
        assert c.post('/v1/me/evidence/1/ocr').status_code==401
        assert c.post('/v1/me/evidence/1/ocr',headers=auth(b)).status_code==404
        r=c.post('/v1/me/evidence/1/ocr',headers=auth(a))
        assert r.status_code==200, r.text
        assert r.json()['suggestions']=={'matches':240,'win_rate':58.3,'kda':5.2,'rank_candidates':['GRANDMASTER']}
        assert c.get('/v1/me',headers=auth(a)).json()['status']=='PENDING_REVIEW'

def test_duplicate_flags_are_private(tmp_path):
    with TestClient(create_app(str(tmp_path/'duplicates.db'),admin_key=ADMIN)) as c:
        a,b=register(c,601),register(c,602)
        for x in (a,b):
            assert c.post('/v1/me/evidence',headers=auth(x),files={'image':('same.png',photo(1),'image/png')}).status_code==200
        assert 'duplicate_slots' not in c.get('/v1/me',headers=auth(a)).json()
        rows=c.get('/v1/admin/accounts',headers=admin()).json()
        assert len(rows)==2 and all(r['duplicate_slots']==[1] for r in rows)
        assert all(r['status']=='PENDING_REVIEW' for r in rows)

def test_decline_cooldown_and_rating_unchanged(tmp_path,monkeypatch):
    import server.app as module
    stamp=module.now_ts()
    monkeypatch.setattr(module,'now_ts',lambda:stamp)
    with TestClient(create_app(str(tmp_path/'cooldown.db'),admin_key=ADMIN)) as c:
        roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
        users=[register(c,800+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(10)]
        for u in users:
            approve(c,u)
            assert c.post('/v1/queue/join',headers=auth(u)).status_code==200
        mid=c.get('/v1/queue/state',headers=auth(users[0])).json()['match']['id']
        assert c.post(f'/v1/matches/{mid}/decline',headers=auth(users[0])).status_code==200
        denied=c.post('/v1/queue/join',headers=auth(users[0]))
        assert denied.status_code==429 and denied.headers['retry-after']=='60'
        state=c.get('/v1/queue/state',headers=auth(users[0])).json()
        assert state['discipline']=={'incidents':1,'blocked_until':stamp+60}
        assert c.get('/v1/me',headers=auth(users[0])).json()['rating_seed']==1600
        monkeypatch.setattr(module,'now_ts',lambda:stamp+61)
        assert c.post('/v1/queue/join',headers=auth(users[0])).status_code==200

def test_registration_quota(tmp_path,monkeypatch):
    import server.app as module
    monkeypatch.setattr(module,'now_ts',lambda:7201)
    with TestClient(create_app(str(tmp_path/'quota.db'),admin_key=ADMIN)) as c:
        for i in range(30):
            register(c,900+i)
        r=c.post('/v1/accounts',json=profile(1000))
        assert r.status_code==429 and r.headers['retry-after']=='3599'
        monkeypatch.setattr(module,'now_ts',lambda:10800)
        assert c.post('/v1/accounts',json=profile(1000)).status_code==201
