"""Authenticated, region-scoped social features for the private-match pilot."""
from fastapi import Depends, HTTPException, Query
from pydantic import BaseModel, Field
from typing import Literal
import uuid

class FriendIn(BaseModel):
    account_id: str = Field(min_length=32,max_length=32)

class InviteIn(BaseModel):
    account_id: str = Field(min_length=32,max_length=32)
    session_id: str = Field(min_length=32,max_length=32)

class ComplaintIn(BaseModel):
    match_id: str = Field(min_length=32,max_length=32)
    account_id: str = Field(min_length=32,max_length=32)
    category: Literal['AFK','ABUSE','CHEATING','GRIEFING','OTHER']
    note: str = Field(default='',max_length=500)

class ReviewIn(BaseModel):
    decision: Literal['UPHELD','DISMISSED']
    note: str = Field(min_length=3,max_length=500)

def install_community(app,store,token_header,admin_auth,limit,get_user,now,session_status):
    with store.transaction(write=True) as db:
        db.executescript("""
          CREATE TABLE IF NOT EXISTS friendships(
            a TEXT NOT NULL REFERENCES accounts(id), b TEXT NOT NULL REFERENCES accounts(id),
            requester TEXT NOT NULL REFERENCES accounts(id), status TEXT NOT NULL, created_at INTEGER NOT NULL,
            PRIMARY KEY(a,b), CHECK(a<b), CHECK(requester=a OR requester=b)
          );
          CREATE TABLE IF NOT EXISTS session_invites(
            id TEXT PRIMARY KEY, sender TEXT NOT NULL REFERENCES accounts(id),
            recipient TEXT NOT NULL REFERENCES accounts(id), session_id TEXT NOT NULL REFERENCES sessions(id),
            status TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(sender,recipient,session_id)
          );
          CREATE INDEX IF NOT EXISTS invites_recipient ON session_invites(recipient,status);
          CREATE TABLE IF NOT EXISTS complaints(
            id TEXT PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(id),
            reporter TEXT NOT NULL REFERENCES accounts(id), target TEXT NOT NULL REFERENCES accounts(id),
            category TEXT NOT NULL,note TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,
            reviewed_at INTEGER, moderator_note TEXT,
            UNIQUE(match_id,reporter,target), CHECK(reporter!=target)
          );
          CREATE INDEX IF NOT EXISTS complaints_status ON complaints(status,created_at);
        """)

    def actor(db,token):
        u=get_user(db,token)
        if u['status']!='APPROVED': raise HTTPException(403,'Требуется допуск')
        return u

    def quota(token,scope,maximum):
        with store.transaction() as db: ident=actor(db,token)['id']
        limit(scope,ident,maximum,3600)

    def peer(db,u,ident):
        p=db.execute("SELECT * FROM accounts WHERE id=? AND region=? AND status='APPROVED'",(ident,u['region'])).fetchone()
        if not p or p['id']==u['id']: raise HTTPException(404,'Игрок не найден')
        return p

    def pair(a,b): return tuple(sorted((a,b)))

    def relation(db,a,b):
        return db.execute('SELECT * FROM friendships WHERE a=? AND b=?',pair(a,b)).fetchone()

    def card(p):
        return {'id':p['id'],'riot_id':p['riot_id'],'region':p['region'],'primary':p['primary_role'],'secondary':p['secondary_role']}

    @app.get('/v1/community/find')
    def find_player(riot_id:str=Query(min_length=5,max_length=64),token=Depends(token_header)):
        quota(token,'community_find',60)
        with store.transaction() as db:
            u=actor(db,token)
            p=db.execute("SELECT * FROM accounts WHERE lower(riot_id)=lower(?) AND region=? AND status='APPROVED' AND id!=?",(riot_id.strip(),u['region'],u['id'])).fetchone()
            if not p: raise HTTPException(404,'Игрок не найден')
            from server.operations import allows_contact
            rel=relation(db,u['id'],p['id'])
            if not rel and not allows_contact(db,p['id']): raise HTTPException(404,'Игрок не найден')
            return dict(card(p),relationship=('FRIEND' if rel['status']=='ACCEPTED' else ('OUTGOING' if rel['requester']==u['id'] else 'INCOMING')) if rel else 'NONE')

    @app.get('/v1/community')
    def overview(token=Depends(token_header)):
        with store.transaction() as db:
            u=actor(db,token); ident=u['id']
            rows=db.execute("""SELECT f.status AS friendship_status,f.requester,p.* FROM friendships f
                JOIN accounts p ON p.id=CASE WHEN f.a=? THEN f.b ELSE f.a END
                WHERE (f.a=? OR f.b=?) AND p.status='APPROVED' AND p.region=? ORDER BY p.riot_id""",(ident,ident,ident,u['region'])).fetchall()
            friends=[dict(card(p),relationship='FRIEND' if p['friendship_status']=='ACCEPTED' else ('OUTGOING' if p['requester']==ident else 'INCOMING')) for p in rows]
            invites=db.execute("""SELECT i.id,i.session_id,i.created_at,s.title,s.starts_at,s.ends_at,a.riot_id AS sender
                FROM session_invites i JOIN sessions s ON s.id=i.session_id JOIN accounts a ON a.id=i.sender
                JOIN friendships f ON (f.a=i.sender AND f.b=i.recipient OR f.b=i.sender AND f.a=i.recipient)
                WHERE i.recipient=? AND i.status='PENDING' AND s.cancelled=0 AND s.ends_at>? AND s.region=?
                  AND a.status='APPROVED' AND a.region=? AND f.status='ACCEPTED' ORDER BY i.created_at DESC LIMIT 50""",(ident,now(),u['region'],u['region'])).fetchall()
            reports=db.execute("SELECT id,match_id,category,status,created_at FROM complaints WHERE reporter=? ORDER BY created_at DESC LIMIT 50",(ident,)).fetchall()
            upheld=db.execute("SELECT count(*) FROM complaints WHERE target=? AND status='UPHELD'",(ident,)).fetchone()[0]
            return {'friends':friends,'invites':[dict(r) for r in invites],'reports':[dict(r) for r in reports],'confirmed_complaints':upheld}

    @app.post('/v1/community/friends/request')
    def request_friend(payload:FriendIn,token=Depends(token_header)):
        quota(token,'friend_request',20)
        with store.transaction(write=True) as db:
            u=actor(db,token); p=peer(db,u,payload.account_id); a,b=pair(u['id'],p['id'])
            from server.operations import allows_contact
            if not allows_contact(db,p['id']): raise HTTPException(404,'Игрок не найден')
            rel=relation(db,a,b)
            if rel: raise HTTPException(409,'Запрос или дружба уже существует')
            count=db.execute('SELECT count(*) FROM friendships WHERE a=? OR b=?',(u['id'],u['id'])).fetchone()[0]
            other=db.execute('SELECT count(*) FROM friendships WHERE a=? OR b=?',(p['id'],p['id'])).fetchone()[0]
            if count>=100 or other>=100: raise HTTPException(409,'Лимит связей достигнут')
            db.execute('INSERT INTO friendships VALUES(?,?,?,?,?)',(a,b,u['id'],'PENDING',now()))
            return {'status':'PENDING'}

    @app.post('/v1/community/friends/{ident}/accept')
    def accept_friend(ident:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=actor(db,token); peer(db,u,ident); rel=relation(db,u['id'],ident)
            if not rel or rel['status']!='PENDING' or rel['requester']==u['id']: raise HTTPException(409,'Нет входящего запроса')
            db.execute("UPDATE friendships SET status='ACCEPTED' WHERE a=? AND b=?",pair(u['id'],ident))
            return {'status':'ACCEPTED'}

    @app.post('/v1/community/friends/{ident}/remove')
    def remove_friend(ident:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=actor(db,token); a,b=pair(u['id'],ident)
            db.execute('DELETE FROM friendships WHERE a=? AND b=?',(a,b))
            db.execute("UPDATE session_invites SET status='DISMISSED' WHERE (sender=? AND recipient=? OR sender=? AND recipient=?) AND status='PENDING'",(a,b,b,a))
            return {'status':'REMOVED'}

    @app.post('/v1/community/invites')
    def invite(payload:InviteIn,token=Depends(token_header)):
        quota(token,'session_invite',30)
        with store.transaction(write=True) as db:
            u=actor(db,token); p=peer(db,u,payload.account_id); rel=relation(db,u['id'],p['id'])
            if not rel or rel['status']!='ACCEPTED': raise HTTPException(403,'Приглашать можно только друзей')
            s=db.execute('SELECT * FROM sessions WHERE id=? AND region=?',(payload.session_id,u['region'])).fetchone()
            if not s or session_status(s) not in ('UPCOMING','OPEN'): raise HTTPException(404,'Сессия недоступна')
            if not db.execute('SELECT 1 FROM session_signups WHERE account_id=? AND session_id=?',(u['id'],s['id'])).fetchone(): raise HTTPException(409,'Сначала запишись на сессию')
            if db.execute('SELECT 1 FROM session_signups WHERE account_id=? AND session_id=?',(p['id'],s['id'])).fetchone(): raise HTTPException(409,'Друг уже записан')
            if db.execute('SELECT 1 FROM session_invites WHERE sender=? AND recipient=? AND session_id=?',(u['id'],p['id'],s['id'])).fetchone(): raise HTTPException(409,'Приглашение уже отправлялось')
            ident=uuid.uuid4().hex
            db.execute('INSERT INTO session_invites VALUES(?,?,?,?,?,?)',(ident,u['id'],p['id'],s['id'],'PENDING',now()))
            return {'id':ident,'status':'PENDING'}

    @app.post('/v1/community/invites/{ident}/dismiss')
    def dismiss_invite(ident:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=actor(db,token)
            n=db.execute("UPDATE session_invites SET status='DISMISSED' WHERE id=? AND recipient=? AND status='PENDING'",(ident,u['id'])).rowcount
            if not n: raise HTTPException(404,'Приглашение не найдено')
            return {'status':'DISMISSED'}

    @app.get('/v1/community/recent-players')
    def recent_players(token=Depends(token_header)):
        with store.transaction() as db:
            u=actor(db,token)
            rows=db.execute("""SELECT m.id AS match_id,m.created_at,m.status,p.id AS account_id,p.riot_id,mp.role
              FROM match_players own JOIN matches m ON m.id=own.match_id
              JOIN match_players mp ON mp.match_id=m.id JOIN accounts p ON p.id=mp.account_id
              WHERE own.account_id=? AND mp.account_id!=? AND m.status IN ('ACTIVE','DISPUTED','CLOSED')
                AND m.created_at>=? ORDER BY m.created_at DESC,m.id,mp.side,mp.role LIMIT 180""",(u['id'],u['id'],now()-7*86400)).fetchall()
            return {'players':[dict(r) for r in rows]}

    @app.post('/v1/community/reports',status_code=201)
    def complain(payload:ComplaintIn,token=Depends(token_header)):
        quota(token,'complaint',12)
        with store.transaction(write=True) as db:
            u=actor(db,token)
            m=db.execute("""SELECT m.id FROM matches m JOIN match_players a ON a.match_id=m.id
              JOIN match_players b ON b.match_id=m.id WHERE m.id=? AND a.account_id=? AND b.account_id=? AND a.account_id!=b.account_id
              AND m.status IN ('ACTIVE','DISPUTED','CLOSED') AND m.created_at>=?""",(payload.match_id,u['id'],payload.account_id,now()-7*86400)).fetchone()
            if not m: raise HTTPException(404,'Совместный матч не найден')
            if db.execute('SELECT 1 FROM complaints WHERE match_id=? AND reporter=? AND target=?',(m['id'],u['id'],payload.account_id)).fetchone(): raise HTTPException(409,'Жалоба уже отправлена')
            ident=uuid.uuid4().hex
            db.execute('INSERT INTO complaints VALUES(?,?,?,?,?,?,?,?,NULL,NULL)',(ident,m['id'],u['id'],payload.account_id,payload.category,payload.note.strip(),'PENDING',now()))
            return {'id':ident,'status':'PENDING'}

    @app.get('/v1/admin/complaints',dependencies=[Depends(admin_auth)])
    def list_complaints():
        with store.transaction() as db:
            rows=db.execute("""SELECT c.*,a.riot_id AS reporter_riot_id,b.riot_id AS target_riot_id
            FROM complaints c JOIN accounts a ON a.id=c.reporter JOIN accounts b ON b.id=c.target
            ORDER BY CASE c.status WHEN 'PENDING' THEN 0 ELSE 1 END,c.created_at DESC LIMIT 200""").fetchall()
            return {'reports':[dict(r) for r in rows]}

    @app.post('/v1/admin/complaints/{ident}/review',dependencies=[Depends(admin_auth)])
    def review_complaint(ident:str,payload:ReviewIn):
        note=payload.note.strip()
        if len(note)<3: raise HTTPException(422,'Укажи основание решения')
        with store.transaction(write=True) as db:
            n=db.execute("UPDATE complaints SET status=?,reviewed_at=?,moderator_note=? WHERE id=? AND status='PENDING'",(payload.decision,now(),note,ident)).rowcount
            if not n: raise HTTPException(409,'Жалоба уже рассмотрена или не найдена')
            return {'status':payload.decision}
