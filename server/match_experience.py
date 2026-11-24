"""Private match coordination and personal statistics, not a Riot integration."""
import hashlib
from fastapi import Depends, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field

class LobbyIn(BaseModel):
    room: str = Field(min_length=3,max_length=64)

def match_details(db, match, own, moderator, stamp):
    mid=match['id']
    room=db.execute('SELECT room FROM match_lobbies WHERE match_id=?',(mid,)).fetchone()
    arrivals=db.execute('SELECT account_id FROM lobby_arrivals WHERE match_id=?',(mid,)).fetchall()
    ids={r['account_id'] for r in arrivals}
    proof=db.execute('SELECT side,created_at FROM result_evidence WHERE match_id=? AND created_at>=?',(mid,stamp-30*86400)).fetchall()
    authorized=own is not None or moderator
    return {
        'server_now':stamp, 'own_ready':bool(own and own['ready']),
        'lobby_host':bool(own and own['role']=='BARON' and own['side']=='BLUE'),
        'lobby_room':room['room'] if authorized and room and match['status']=='ACTIVE' else None,
        'lobby_arrivals':len(ids), 'own_arrived':bool(own and own['account_id'] in ids),
        'evidence_sides':[r['side'] for r in proof] if authorized else [],
        'own_evidence':bool(own and any(r['side']==own['side'] for r in proof)),
    }

def install_match_experience(app,store,token_header,admin_auth,limit,match_member,get_user,now,read_image,max_bytes):
    with store.transaction(write=True) as db:
        db.executescript("""
          CREATE TABLE IF NOT EXISTS match_lobbies(
            match_id TEXT PRIMARY KEY REFERENCES matches(id), room TEXT NOT NULL, updated_at INTEGER NOT NULL
          );
          CREATE TABLE IF NOT EXISTS lobby_arrivals(
            match_id TEXT NOT NULL REFERENCES matches(id),account_id TEXT NOT NULL REFERENCES accounts(id),
            arrived_at INTEGER NOT NULL,PRIMARY KEY(match_id,account_id)
          );
          CREATE TABLE IF NOT EXISTS result_evidence(
            match_id TEXT NOT NULL REFERENCES matches(id),side TEXT NOT NULL,account_id TEXT NOT NULL REFERENCES accounts(id),
            content BLOB NOT NULL,sha256 TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(match_id,side)
          );
          CREATE INDEX IF NOT EXISTS result_evidence_age ON result_evidence(created_at);
        """)

    def member(db,token,mid,statuses):
        user,mp=match_member(db,token,mid)
        match=db.execute('SELECT * FROM matches WHERE id=?',(mid,)).fetchone()
        if match['status'] not in statuses:
            raise HTTPException(409,'Действие недоступно на этой стадии матча')
        return user,mp

    @app.put('/v1/matches/{mid}/lobby')
    def set_lobby(mid:str,payload:LobbyIn,token=Depends(token_header)):
        room=payload.room.strip()
        if len(room)<3 or any(ord(c)<32 for c in room) or '://' in room:
            raise HTTPException(422,'Укажи название лобби, не ссылку или пароль аккаунта')
        with store.transaction(write=True) as db:
            _,mp=member(db,token,mid,('ACTIVE',))
            if mp['side']!='BLUE' or mp['role']!='BARON': raise HTTPException(403,'Лобби назначает капитан синих')
            existing=db.execute('SELECT room FROM match_lobbies WHERE match_id=?',(mid,)).fetchone()
            if existing and existing['room']==room: return {'room':room}
            if db.execute('SELECT 1 FROM lobby_arrivals WHERE match_id=? LIMIT 1',(mid,)).fetchone():
                raise HTTPException(409,'Участники уже вошли; название зафиксировано')
            db.execute('INSERT INTO match_lobbies VALUES(?,?,?) ON CONFLICT(match_id) DO UPDATE SET room=excluded.room,updated_at=excluded.updated_at',(mid,room,now()))
            return {'room':room}

    @app.post('/v1/matches/{mid}/lobby/arrive')
    def arrive(mid:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user,_=member(db,token,mid,('ACTIVE',))
            if not db.execute('SELECT 1 FROM match_lobbies WHERE match_id=?',(mid,)).fetchone():
                raise HTTPException(409,'Капитан ещё не указал лобби')
            db.execute('INSERT OR IGNORE INTO lobby_arrivals VALUES(?,?,?)',(mid,user['id'],now()))
            count=db.execute('SELECT count(*) FROM lobby_arrivals WHERE match_id=?',(mid,)).fetchone()[0]
            return {'arrived':count}

    @app.post('/v1/matches/{mid}/evidence')
    async def upload_result(mid:str,image:UploadFile=File(...),token=Depends(token_header)):
        with store.transaction() as db:
            user,mp=member(db,token,mid,('ACTIVE','DISPUTED'))
            if mp['role']!='BARON': raise HTTPException(403,'Скриншот отправляет капитан')
            ident=user['id']
        limit('result_evidence',ident,12,3600)
        content=read_image(await image.read(max_bytes+1))
        hashed=hashlib.sha256(content).hexdigest()
        with store.transaction(write=True) as db:
            user,mp=member(db,token,mid,('ACTIVE','DISPUTED'))
            if mp['role']!='BARON': raise HTTPException(403,'Скриншот отправляет капитан')
            db.execute('DELETE FROM result_evidence WHERE created_at<?',(now()-30*86400,))
            existing=db.execute('SELECT sha256 FROM result_evidence WHERE match_id=? AND side=?',(mid,mp['side'])).fetchone()
            if existing:
                if existing['sha256']==hashed: return {'side':mp['side'],'status':'STORED'}
                raise HTTPException(409,'Доказательство уже сохранено и не заменяется')
            db.execute('INSERT INTO result_evidence VALUES(?,?,?,?,?,?)',(mid,mp['side'],user['id'],content,hashed,now()))
            db.execute('INSERT OR IGNORE INTO result_proof_history VALUES(?,?,?)',(mid,mp['side'],hashed))
            return {'side':mp['side'],'status':'STORED'}

    @app.get('/v1/admin/matches/{mid}/evidence/{side}',dependencies=[Depends(admin_auth)])
    def review_evidence(mid:str,side:str):
        with store.transaction() as db:
            row=db.execute('SELECT content FROM result_evidence WHERE match_id=? AND side=? AND created_at>=?',(mid,side,now()-30*86400)).fetchone()
            if not row: raise HTTPException(404,'Доказательство не найдено')
            return Response(row['content'],media_type='image/jpeg',headers={'Cache-Control':'no-store'})

    @app.get('/v1/me/statistics')
    def statistics(token=Depends(token_header)):
        with store.transaction() as db:
            user=get_user(db,token)
            rows=db.execute("""SELECT p.role,p.side,r.winner,r.resolved_at FROM match_results r
              JOIN match_players p ON p.match_id=r.match_id WHERE p.account_id=?
              ORDER BY r.resolved_at DESC,r.match_id DESC""",(user['id'],)).fetchall()
            roles={role:{'played':0,'wins':0,'losses':0} for role in ('BARON','JUNGLE','MID','DRAGON','SUPPORT')}
            wins=losses=voids=0
            for row in rows:
                if row['winner']=='VOID': voids+=1; continue
                won=row['winner']==row['side']
                wins+=int(won); losses+=int(not won)
                role=roles[row['role']]
                role['played']+=1; role['wins']+=int(won); role['losses']+=int(not won)
            count=wins+losses
            from server.match_intelligence import role_estimates
            estimates=role_estimates(db,user['id'],user['rating'])
            return {'played':count,'wins':wins,'losses':losses,'voids':voids,
                'role_estimates':estimates,
                'win_rate':round(100*wins/count,1) if count else None,
                'calibration':{'completed':min(count,10),'target':10,'provisional':count<10},
                'roles':roles}
