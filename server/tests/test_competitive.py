from fastapi.testclient import TestClient
from server.app import create_app
from server.tests.test_api import ADMIN, auth, admin, register, approve
import server.app as module

def test_team_challenge_and_cup(tmp_path,monkeypatch):
    stamp=module.now_ts()
    clock=[stamp]
    monkeypatch.setattr(module,'now_ts',lambda:clock[0])
    with TestClient(create_app(str(tmp_path/'club.db'),admin_key=ADMIN)) as c:
        roles=['BARON','JUNGLE','MID','DRAGON','SUPPORT']
        users=[register(c,3000+i,role=roles[i%5],secondary=roles[(i+1)%5]) for i in range(21)]
        for u in users: approve(c,u)
        a=admin()
        teams=[]
        for i in range(4):
            captain=users[i*5]
            t=c.post('/v1/competitive/teams',headers=auth(captain),json={'name':f'Team {i}'}).json()
            teams.append(t['id'])
            for u in users[i*5+1:i*5+5]:
                assert c.post('/v1/competitive/team/invite',headers=auth(captain),json={'riot_id':u['account']['riot_id']}).status_code==200
                invite=c.get('/v1/competitive',headers=auth(u)).json()['invites'][0]['id']
                assert c.post(f'/v1/competitive/invites/{invite}/accept',headers=auth(users[-1])).status_code==404
                assert c.post(f'/v1/competitive/invites/{invite}/accept',headers=auth(u)).status_code==200
        path='/v1/admin/competitive/seasons'
        data={'name':'Season 1','region':'EUROPE','starts_at':stamp,'ends_at':stamp+86400}
        assert c.post(path,headers=auth(users[0]),json=data).status_code==403
        sid=c.post(path,headers=a,json=data).json()['id']
        assert c.post(path,headers=a,json=data).status_code==409
        h=auth(users[0]);h2=auth(users[5])
        r=c.post('/v1/competitive/challenges',headers=h,json={'season_id':sid,'opponent_id':teams[1],'starts_at':stamp+120})
        assert r.status_code==201,r.text
        fid=r.json()['id']
        assert c.post('/v1/competitive/team/leave',headers=auth(users[1])).status_code==409
        assert c.get('/v1/competitive',headers=h2).json()['my_team']['locked'] is False
        assert c.post(f'/v1/competitive/challenges/{fid}/accept',headers=h).status_code==403
        assert c.post('/v1/queue/join',headers=h).status_code==200
        assert c.post(f'/v1/competitive/challenges/{fid}/accept',headers=h2).status_code==200
        assert not c.get('/v1/queue/state',headers=h).json()['queued']
        assert c.post('/v1/queue/join',headers=h).status_code==409
        assert c.post('/v1/queue/join',headers=h2).status_code==409
        assert c.post('/v1/queue/join',headers=auth(users[-1])).status_code==200
        route=f'/v1/admin/competitive/fixtures/{fid}/resolve'
        result={'winner_id':teams[0],'note':'Verified synthetic result'}
        assert c.post(route,headers=a,json=result).status_code==409
        clock[0]+=120
        assert c.post(route,headers=a,json=result).status_code==200
        assert c.post(route,headers=a,json=result).status_code==409
        assert c.get('/v1/me',headers=h).json()['rating_seed']==1600
        assert c.post('/v1/queue/join',headers=h).status_code==200
        c.post('/v1/queue/leave',headers=h)
        cup=c.post('/v1/admin/competitive/cups',headers=a,json={'name':'Cup 1','season_id':sid,'teams':teams,'starts_at':clock[0]+120})
        assert cup.status_code==201,cup.text
        cid=cup.json()['id'];clock[0]+=120
        overview=c.get('/v1/admin/competitive',headers=a).json()
        semis=[f for f in overview['fixtures'] if f['cup_id']==cid]
        assert len(semis)==2
        for f in semis:
            assert c.post(f"/v1/admin/competitive/fixtures/{f['id']}/resolve",headers=a,json={'winner_id':f['team_a'],'note':'Verified result'}).status_code==200
        final=next(f for f in c.get('/v1/admin/competitive',headers=a).json()['fixtures'] if f['stage']=='FINAL')
        assert {final['team_a'],final['team_b']}=={teams[0],teams[2]}
        assert c.post('/v1/competitive/team/leave',headers=auth(users[1])).status_code==409
        clock[0]=final['starts_at']
        assert c.post(f"/v1/admin/competitive/fixtures/{final['id']}/resolve",headers=a,json={'winner_id':teams[0],'note':'Verified final'}).status_code==200
        assert c.get('/v1/competitive',headers=h).json()['cups'][0]['winner_id']==teams[0]
        assert c.post('/v1/competitive/team/leave',headers=auth(users[1])).status_code==200
        other=c.get('/v1/competitive',headers=auth(users[-1])).json()
        assert all('members' not in t for t in other['teams'])
        assert all('roster_a' not in f for f in other['fixtures'])
        assert next(t for t in other['teams'] if t['id']==teams[0])['wins']==3
        assert other['standings'][sid][0]=={'id':teams[0],'name':'Team 0','played':3,'wins':3,'losses':0}
        assert sum(row['wins'] for row in other['standings'][sid])==4
        assert sum(row['losses'] for row in other['standings'][sid])==4
