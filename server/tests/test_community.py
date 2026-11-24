from fastapi.testclient import TestClient
from server.app import create_app, now_ts
from server.tests.test_api import ADMIN, register, auth, admin, approve, profile
from server.tests.test_results import setup_match

def test_friend_invite_authorization(tmp_path):
    with TestClient(create_app(str(tmp_path/'social.db'),admin_key=ADMIN)) as c:
        a,b,foreign,pending=[register(c,i,region='ASIA' if i==1802 else 'EUROPE') for i in range(1800,1804)]
        for u in (a,b,foreign): approve(c,u)
        aid,bid=a['account']['id'],b['account']['id']
        assert c.get('/v1/community').status_code==401
        assert c.get('/v1/community',headers=auth(pending)).status_code==403
        assert c.get('/v1/community/find',headers=auth(a),params={'riot_id':'Pilot1802#EUW'}).status_code==404
        found=c.get('/v1/community/find',headers=auth(a),params={'riot_id':'Pilot1801#EUW'}).json()
        assert found['id']==bid and 'token_hash' not in found and 'rating_seed' not in found
        assert c.post('/v1/community/friends/request',headers=auth(a),json={'account_id':bid}).status_code==200
        assert c.post(f'/v1/community/friends/{bid}/accept',headers=auth(a)).status_code==409
        assert c.post(f'/v1/community/friends/{aid}/accept',headers=auth(b)).status_code==200
        assert c.get('/v1/community',headers=auth(a)).json()['friends'][0]['relationship']=='FRIEND'
        assert c.put('/v1/me',headers=auth(a),json=profile(1999)).status_code==409
        t=now_ts()+60
        sid=c.post('/v1/admin/sessions',headers=admin(),json={'title':'Friends practice','region':'EUROPE','starts_at':t,'ends_at':t+600,'capacity':10}).json()['id']
        assert c.post('/v1/community/invites',headers=auth(a),json={'account_id':bid,'session_id':sid}).status_code==409
        assert c.post(f'/v1/sessions/{sid}/signup',headers=auth(a)).status_code==200
        invite=c.post('/v1/community/invites',headers=auth(a),json={'account_id':bid,'session_id':sid})
        assert invite.status_code==200,invite.text
        assert not c.get('/v1/sessions',headers=auth(b)).json()['sessions'][0]['enrolled']
        iid=invite.json()['id']
        assert c.post(f'/v1/community/invites/{iid}/dismiss',headers=auth(a)).status_code==404
        assert c.get('/v1/community',headers=auth(b)).json()['invites'][0]['id']==iid
        assert c.post(f'/v1/community/friends/{bid}/remove',headers=auth(a)).status_code==200
        assert c.get('/v1/community',headers=auth(b)).json()['invites']==[]

def test_match_complaints_review_private_and_no_rating_change(tmp_path):
    with TestClient(create_app(str(tmp_path/'reports.db'),admin_key=ADMIN)) as c:
        mid,users,b,r,other=setup_match(c)
        outsider=register(c,1900);approve(c,outsider)
        payload={'match_id':mid,'account_id':r['account']['id'],'category':'AFK','note':'Synthetic test'}
        assert c.post('/v1/community/reports',headers=auth(outsider),json=payload).status_code==404
        assert len(c.get('/v1/community/recent-players',headers=auth(b)).json()['players'])==9
        res=c.post('/v1/community/reports',headers=auth(b),json=payload)
        assert res.status_code==201,res.text
        assert c.post('/v1/community/reports',headers=auth(b),json=payload).status_code==409
        assert c.get('/v1/community',headers=auth(r)).json()['reports']==[]
        assert c.get('/v1/admin/complaints',headers=auth(r)).status_code==403
        ident=res.json()['id'];path=f'/v1/admin/complaints/{ident}/review'
        assert c.post(path,headers=auth(b),json={'decision':'UPHELD','note':'Reviewed'}).status_code==403
        assert c.post(path,headers=admin(),json={'decision':'UPHELD','note':'Reviewed'}).status_code==200
        assert c.post(path,headers=admin(),json={'decision':'DISMISSED','note':'Reviewed again'}).status_code==409
        assert c.get('/v1/community',headers=auth(r)).json()['confirmed_complaints']==1
        assert c.get('/v1/community',headers=auth(b)).json()['reports'][0]['status']=='UPHELD'
        assert c.get('/v1/me',headers=auth(r)).json()['rating_seed']==1600
