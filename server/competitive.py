"""Independent fixed-roster competitions. No Riot automation or individual Elo changes."""
import json
import uuid
from typing import Literal
from fastapi import Depends, HTTPException
from pydantic import BaseModel, Field

class NameIn(BaseModel):
    name:str=Field(min_length=3,max_length=48)
class InviteIn(BaseModel):
    riot_id:str=Field(min_length=5,max_length=64)
class SeasonIn(NameIn):
    region:Literal['EUROPE','ASIA','AMERICAS']
    starts_at:int
    ends_at:int
class ChallengeIn(BaseModel):
    opponent_id:str
    season_id:str
    starts_at:int
class CupIn(NameIn):
    season_id:str
    teams:list[str]=Field(min_length=4,max_length=4)
    starts_at:int
class DecisionIn(BaseModel):
    winner_id:str
    note:str=Field(min_length=3,max_length=500)

def install_competitive(app,store,token_header,admin_auth,limit,get_user,now):
    with store.transaction(write=True) as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS club_teams(
          id TEXT PRIMARY KEY,name TEXT NOT NULL,region TEXT NOT NULL,
          captain TEXT NOT NULL REFERENCES accounts(id),archived INTEGER NOT NULL DEFAULT 0);
        CREATE UNIQUE INDEX IF NOT EXISTS club_team_name ON club_teams(region,lower(name)) WHERE archived=0;
        CREATE TABLE IF NOT EXISTS club_members(
          account_id TEXT PRIMARY KEY REFERENCES accounts(id),team_id TEXT NOT NULL REFERENCES club_teams(id));
        CREATE INDEX IF NOT EXISTS club_roster ON club_members(team_id);
        CREATE TABLE IF NOT EXISTS club_invites(
          id TEXT PRIMARY KEY,team_id TEXT NOT NULL REFERENCES club_teams(id),recipient TEXT NOT NULL REFERENCES accounts(id),
          created_at INTEGER NOT NULL, UNIQUE(team_id,recipient));
        CREATE TABLE IF NOT EXISTS club_seasons(
          id TEXT PRIMARY KEY,name TEXT NOT NULL,region TEXT NOT NULL,starts_at INTEGER NOT NULL,ends_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS club_cups(
          id TEXT PRIMARY KEY,name TEXT NOT NULL,season_id TEXT NOT NULL REFERENCES club_seasons(id),
          status TEXT NOT NULL,winner_id TEXT REFERENCES club_teams(id),starts_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS club_cup_entries(
          cup_id TEXT NOT NULL REFERENCES club_cups(id),team_id TEXT NOT NULL REFERENCES club_teams(id),
          PRIMARY KEY(cup_id,team_id));
        CREATE TABLE IF NOT EXISTS club_fixtures(
          id TEXT PRIMARY KEY,season_id TEXT NOT NULL REFERENCES club_seasons(id),
          cup_id TEXT REFERENCES club_cups(id),stage TEXT NOT NULL,team_a TEXT NOT NULL REFERENCES club_teams(id),
          team_b TEXT NOT NULL REFERENCES club_teams(id),starts_at INTEGER NOT NULL,status TEXT NOT NULL,
          roster_a TEXT NOT NULL,roster_b TEXT NOT NULL,winner_id TEXT REFERENCES club_teams(id),note TEXT,
          CHECK(team_a!=team_b));
        CREATE INDEX IF NOT EXISTS club_fixtures_season ON club_fixtures(season_id,starts_at);
        """)

    def uid(): return uuid.uuid4().hex
    def fail(code,msg): raise HTTPException(code,msg)
    def actor(db,token):
        u=get_user(db,token)
        if u['status']!='APPROVED': fail(403,'Требуется допуск')
        return u
    def quota(token,scope,maximum=20):
        with store.transaction() as db: ident=actor(db,token)['id']
        limit(scope,ident,maximum,3600)
    def name(value):
        value=value.strip()
        if len(value)<3 or any(ord(ch)<32 for ch in value): fail(422,'Некорректное название')
        return value
    def expire(db):
        db.execute("UPDATE club_fixtures SET status='EXPIRED' WHERE status='PENDING' AND starts_at<=?",(now(),))
        db.execute('DELETE FROM club_invites WHERE created_at<?',(now()-7*86400,))
    def team(db,ident):
        t=db.execute('SELECT * FROM club_teams WHERE id=? AND archived=0',(ident,)).fetchone()
        if not t: fail(404,'Команда не найдена')
        return t
    def own(db,u):
        t=db.execute('SELECT t.* FROM club_teams t JOIN club_members m ON m.team_id=t.id WHERE m.account_id=? AND t.archived=0',(u['id'],)).fetchone()
        return t
    def captain(db,u):
        t=own(db,u)
        if not t or t['captain']!=u['id']: fail(403,'Действие доступно капитану')
        return t
    def roster(db,t):
        return [dict(r) for r in db.execute("""SELECT a.id,a.riot_id,a.status,a.region,a.primary_role AS role
          FROM club_members m JOIN accounts a ON a.id=m.account_id WHERE m.team_id=? ORDER BY a.id""",(t['id'],))]
    def full(db,t):
        rows=roster(db,t)
        if len(rows)!=5 or any(r['status']!='APPROVED' or r['region']!=t['region'] for r in rows):
            fail(409,'Нужны пять допущенных участников одного региона')
        return [{'id':r['id'],'riot_id':r['riot_id'],'role':r['role']} for r in rows]
    def locked(db,tid):
        return bool(db.execute("""SELECT 1 FROM club_fixtures WHERE ((team_a=? AND status='PENDING') OR ((team_a=? OR team_b=?) AND status='SCHEDULED'))
          UNION ALL SELECT 1 FROM club_cup_entries e JOIN club_cups c ON c.id=e.cup_id WHERE e.team_id=? AND c.status='RUNNING' LIMIT 1""",(tid,tid,tid,tid)).fetchone())
    def unlocked(db,tid):
        if locked(db,tid): fail(409,'Состав зафиксирован до завершения встречи или турнира')
    def card(db,t):
        rows=roster(db,t)
        stats=db.execute("""SELECT count(*) AS played,coalesce(sum(winner_id=?),0) AS wins FROM club_fixtures
          WHERE status='FINISHED' AND (team_a=? OR team_b=?)""",(t['id'],t['id'],t['id'])).fetchone()
        return {'id':t['id'],'name':t['name'],'region':t['region'],'captain':t['captain'],
          'members':rows,'locked':locked(db,t['id']),'played':stats['played'],'wins':stats['wins'],
          'eligible':len(rows)==5 and all(r['status']=='APPROVED' and r['region']==t['region'] for r in rows)}
    def season(db,sid):
        s=db.execute('SELECT * FROM club_seasons WHERE id=?',(sid,)).fetchone()
        if not s: fail(404,'Сезон не найден')
        return s
    def scheduled(s,start):
        if start<now()+60 or start<s['starts_at'] or start>=s['ends_at']:
            fail(422,'Встреча должна начаться в будущем и в пределах сезона')
    def fixture(db,ident):
        f=db.execute('SELECT * FROM club_fixtures WHERE id=?',(ident,)).fetchone()
        if not f: fail(404,'Встреча не найдена')
        return f
    def fixture_view(db,f,private=False):
        d=dict(f)
        for key in ('team_a','team_b'):
            d[key+'_name']=db.execute('SELECT name FROM club_teams WHERE id=?',(f[key],)).fetchone()['name']
        for key in ('roster_a','roster_b'):
            rows=json.loads(d.pop(key))
            if private: d[key]=rows
        if not private: d.pop('note',None)
        return d
    def insert_fixture(db,sid,cid,stage,a,b,start,status,ra,rb):
        ident=uid()
        db.execute('INSERT INTO club_fixtures VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)',
          (ident,sid,cid,stage,a,b,start,status,json.dumps(ra),json.dumps(rb)))
        return ident
    def overview(db,region,u=None):
        args=(region,) if region else ()
        region_where=' WHERE region=?' if region else ''
        ss=db.execute('SELECT * FROM club_seasons'+region_where+' ORDER BY starts_at DESC LIMIT 50',args).fetchall()
        teams=db.execute('SELECT * FROM club_teams WHERE archived=0'+(' AND region=?' if region else '')+' ORDER BY name LIMIT 100',args).fetchall()
        fs=db.execute('SELECT f.* FROM club_fixtures f JOIN club_seasons s ON s.id=f.season_id'+(' WHERE s.region=?' if region else '')+' ORDER BY f.starts_at DESC LIMIT 100',args).fetchall()
        cs=db.execute('SELECT c.* FROM club_cups c JOIN club_seasons s ON s.id=c.season_id'+(' WHERE s.region=?' if region else '')+' ORDER BY c.starts_at DESC LIMIT 50',args).fetchall()
        my=own(db,u) if u else None
        invites=[] if not u else [dict(r) for r in db.execute("""SELECT i.id,t.name,t.id AS team_id FROM club_invites i
           JOIN club_teams t ON t.id=i.team_id WHERE i.recipient=? AND t.archived=0 AND t.region=?""",(u['id'],region))]
        return {'seasons':[dict(s) for s in ss],'teams':[card(db,t) if u is None or (my and my['id']==t['id']) else
           {k:v for k,v in card(db,t).items() if k!='members' and k!='captain'} for t in teams],
           'my_team':card(db,my) if my else None,'is_captain':bool(my and my['captain']==u['id']),
           'invites':invites,'fixtures':[fixture_view(db,f,u is None or bool(my and my['id'] in (f['team_a'],f['team_b']))) for f in fs],
           'cups':[dict(c) for c in cs]}

    @app.get('/v1/competitive')
    def get_competitive(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=actor(db,token); expire(db)
            return overview(db,u['region'],u)

    @app.post('/v1/competitive/teams',status_code=201)
    def create_team(payload:NameIn,token=Depends(token_header)):
        quota(token,'club_create',5)
        with store.transaction(write=True) as db:
            u=actor(db,token); n=name(payload.name)
            if own(db,u): fail(409,'Ты уже в команде')
            if db.execute('SELECT 1 FROM club_teams WHERE region=? AND lower(name)=lower(?) AND archived=0',(u['region'],n)).fetchone(): fail(409,'Название уже занято')
            ident=uid()
            db.execute('INSERT INTO club_teams VALUES(?,?,?,?,0)',(ident,n,u['region'],u['id']))
            db.execute('INSERT INTO club_members VALUES(?,?)',(u['id'],ident))
            db.execute('DELETE FROM club_invites WHERE recipient=?',(u['id'],))
            return card(db,team(db,ident))

    @app.post('/v1/competitive/team/invite')
    def invite(payload:InviteIn,token=Depends(token_header)):
        quota(token,'club_invite')
        with store.transaction(write=True) as db:
            u=actor(db,token); expire(db); t=captain(db,u); unlocked(db,t['id'])
            if len(roster(db,t))>=5: fail(409,'Состав заполнен')
            p=db.execute("SELECT * FROM accounts WHERE lower(riot_id)=lower(?) AND region=? AND status='APPROVED'",(payload.riot_id.strip(),u['region'])).fetchone()
            if not p or p['id']==u['id']: fail(404,'Игрок не найден')
            if own(db,p): fail(409,'Игрок уже в команде')
            db.execute('INSERT OR IGNORE INTO club_invites VALUES(?,?,?,?)',(uid(),t['id'],p['id'],now()))
            return {'status':'PENDING'}

    @app.post('/v1/competitive/invites/{ident}/{action}')
    def invite_answer(ident:str,action:Literal['accept','dismiss'],token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=actor(db,token); expire(db)
            i=db.execute('SELECT * FROM club_invites WHERE id=? AND recipient=?',(ident,u['id'])).fetchone()
            if not i: fail(404,'Приглашение не найдено')
            if action=='accept':
                t=team(db,i['team_id']); unlocked(db,t['id'])
                if t['region']!=u['region'] or own(db,u) or len(roster(db,t))>=5: fail(409,'Вступление недоступно')
                if not db.execute("SELECT 1 FROM accounts WHERE id=? AND status='APPROVED'",(t['captain'],)).fetchone(): fail(409,'Капитан не допущен')
                db.execute('INSERT INTO club_members VALUES(?,?)',(u['id'],t['id']))
                db.execute('DELETE FROM club_invites WHERE recipient=?',(u['id'],))
            else: db.execute('DELETE FROM club_invites WHERE id=?',(ident,))
            return {'status':action.upper()}

    @app.post('/v1/competitive/team/leave')
    def leave(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=get_user(db,token); expire(db); t=own(db,u)
            if not t: fail(404,'Ты не в команде')
            unlocked(db,t['id'])
            if t['captain']==u['id']:
                if len(roster(db,t))>1: fail(409,'Передай капитанство или исключи участников')
                db.execute('UPDATE club_teams SET archived=1 WHERE id=?',(t['id'],))
                db.execute('DELETE FROM club_invites WHERE team_id=?',(t['id'],))
            db.execute('DELETE FROM club_members WHERE account_id=?',(u['id'],))
            return {'status':'LEFT'}

    @app.post('/v1/competitive/team/members/{ident}/{action}')
    def member_action(ident:str,action:Literal['remove','captain'],token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=get_user(db,token); expire(db); t=captain(db,u); unlocked(db,t['id'])
            if ident==u['id'] or not db.execute('SELECT 1 FROM club_members WHERE account_id=? AND team_id=?',(ident,t['id'])).fetchone(): fail(404,'Участник не найден')
            if action=='captain':
                if not db.execute("SELECT 1 FROM accounts WHERE id=? AND status='APPROVED'",(ident,)).fetchone(): fail(409,'Игрок не допущен')
                db.execute('UPDATE club_teams SET captain=? WHERE id=?',(ident,t['id']))
            else: db.execute('DELETE FROM club_members WHERE account_id=?',(ident,))
            return {'status':'OK'}

    @app.post('/v1/competitive/challenges',status_code=201)
    def challenge(payload:ChallengeIn,token=Depends(token_header)):
        quota(token,'club_challenge',10)
        with store.transaction(write=True) as db:
            u=actor(db,token); expire(db); a=captain(db,u); b=team(db,payload.opponent_id); s=season(db,payload.season_id)
            if a['id']==b['id'] or a['region']!=b['region'] or s['region']!=a['region']: fail(409,'Команды и сезон должны быть одного региона')
            scheduled(s,payload.starts_at); unlocked(db,a['id']); unlocked(db,b['id'])
            ident=insert_fixture(db,s['id'],None,'CHALLENGE',a['id'],b['id'],payload.starts_at,'PENDING',full(db,a),full(db,b))
            return fixture_view(db,fixture(db,ident),True)

    @app.post('/v1/competitive/challenges/{ident}/{action}')
    def answer_challenge(ident:str,action:Literal['accept','decline'],token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=actor(db,token); expire(db); t=captain(db,u); f=fixture(db,ident)
            if f['stage']!='CHALLENGE' or f['status']!='PENDING' or t['id'] not in (f['team_a'],f['team_b']): fail(409,'Вызов недоступен')
            if action=='accept':
                if t['id']!=f['team_b']: fail(403,'Подтверждает капитан приглашённой команды')
                unlocked(db,t['id'])
                full(db,team(db,f['team_a']));rb=full(db,team(db,f['team_b']))
                db.execute('UPDATE club_fixtures SET roster_b=? WHERE id=?',(json.dumps(rb),ident))
            db.execute('UPDATE club_fixtures SET status=? WHERE id=?',('SCHEDULED' if action=='accept' else 'CANCELLED',ident))
            return {'status':'SCHEDULED' if action=='accept' else 'CANCELLED'}

    @app.get('/v1/admin/competitive',dependencies=[Depends(admin_auth)])
    def admin_overview():
        with store.transaction(write=True) as db:
            expire(db); return overview(db,None)

    @app.post('/v1/admin/competitive/seasons',status_code=201,dependencies=[Depends(admin_auth)])
    def create_season(payload:SeasonIn):
        n=name(payload.name)
        if payload.starts_at<now()-60 or not 86400<=payload.ends_at-payload.starts_at<=180*86400 or payload.starts_at>now()+30*86400: fail(422,'Сезон: 1–180 дней, начало в пределах 30 дней')
        with store.transaction(write=True) as db:
            if db.execute('SELECT 1 FROM club_seasons WHERE region=? AND starts_at<? AND ends_at>?',(payload.region,payload.ends_at,payload.starts_at)).fetchone(): fail(409,'Сезоны пересекаются')
            ident=uid()
            db.execute('INSERT INTO club_seasons VALUES(?,?,?,?,?)',(ident,n,payload.region,payload.starts_at,payload.ends_at))
            return {'id':ident}

    @app.post('/v1/admin/competitive/cups',status_code=201,dependencies=[Depends(admin_auth)])
    def create_cup(payload:CupIn):
        with store.transaction(write=True) as db:
            expire(db); s=season(db,payload.season_id); scheduled(s,payload.starts_at)
            if payload.starts_at+3600>=s['ends_at'] or len(set(payload.teams))!=4: fail(422,'Нужны четыре разные команды и время на финал')
            ts=[team(db,i) for i in payload.teams]
            if any(t['region']!=s['region'] for t in ts): fail(409,'Разные регионы')
            for t in ts: unlocked(db,t['id'])
            rosters=[full(db,t) for t in ts]; ident=uid()
            db.execute('INSERT INTO club_cups VALUES(?,?,?,\'RUNNING\',NULL,?)',(ident,name(payload.name),s['id'],payload.starts_at))
            for t in ts: db.execute('INSERT INTO club_cup_entries VALUES(?,?)',(ident,t['id']))
            for i,stage in ((0,'SEMIFINAL_1'),(2,'SEMIFINAL_2')):
                insert_fixture(db,s['id'],ident,stage,ts[i]['id'],ts[i+1]['id'],payload.starts_at,'SCHEDULED',rosters[i],rosters[i+1])
            return {'id':ident}

    @app.post('/v1/admin/competitive/fixtures/{ident}/resolve',dependencies=[Depends(admin_auth)])
    def resolve(ident:str,payload:DecisionIn):
        with store.transaction(write=True) as db:
            f=fixture(db,ident); note=payload.note.strip()
            if f['status']!='SCHEDULED' or now()<f['starts_at']: fail(409,'Встреча ещё не началась или уже закрыта')
            if payload.winner_id not in (f['team_a'],f['team_b']) or len(note)<3: fail(422,'Укажи победителя и основание')
            db.execute("UPDATE club_fixtures SET status='FINISHED',winner_id=?,note=? WHERE id=?",(payload.winner_id,note,ident))
            if f['cup_id']:
                if f['stage']=='FINAL':
                    db.execute("UPDATE club_cups SET status='FINISHED',winner_id=? WHERE id=?",(payload.winner_id,f['cup_id']))
                else:
                    semis=db.execute("SELECT * FROM club_fixtures WHERE cup_id=? AND stage IN ('SEMIFINAL_1','SEMIFINAL_2') AND status='FINISHED' ORDER BY stage",(f['cup_id'],)).fetchall()
                    if len(semis)==2:
                        def winning_roster(x): return json.loads(x['roster_a'] if x['winner_id']==x['team_a'] else x['roster_b'])
                        start=max(semis[0]['starts_at']+3600,now()+60)
                        # A delayed final stays part of the original season, even if resolved after its end.
                        insert_fixture(db,f['season_id'],f['cup_id'],'FINAL',semis[0]['winner_id'],semis[1]['winner_id'],start,'SCHEDULED',winning_roster(semis[0]),winning_roster(semis[1]))
            return {'status':'FINISHED'}

    @app.post('/v1/admin/competitive/fixtures/{ident}/cancel',dependencies=[Depends(admin_auth)])
    def cancel_fixture(ident:str):
        with store.transaction(write=True) as db:
            f=fixture(db,ident)
            if f['cup_id'] or f['status'] not in ('PENDING','SCHEDULED'): fail(409,'Отмени турнир целиком либо выбери открытую встречу')
            db.execute("UPDATE club_fixtures SET status='CANCELLED' WHERE id=?",(ident,))
            return {'status':'CANCELLED'}

    @app.post('/v1/admin/competitive/cups/{ident}/cancel',dependencies=[Depends(admin_auth)])
    def cancel_cup(ident:str):
        with store.transaction(write=True) as db:
            n=db.execute("UPDATE club_cups SET status='CANCELLED' WHERE id=? AND status='RUNNING'",(ident,)).rowcount
            if not n: fail(409,'Турнир уже закрыт или не найден')
            db.execute("UPDATE club_fixtures SET status='CANCELLED' WHERE cup_id=? AND status='SCHEDULED'",(ident,))
            return {'status':'CANCELLED'}
