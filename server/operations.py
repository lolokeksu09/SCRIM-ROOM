"""Pilot operations: single-use recovery, private notification inbox and bounded admin audit."""
import hashlib
import logging
import secrets
import sqlite3
from fastapi import Depends, HTTPException, Request
from pydantic import BaseModel, Field

log=logging.getLogger(__name__)
def digest(value): return hashlib.sha256(value.encode()).hexdigest()

class RecoverIn(BaseModel):
    code:str=Field(min_length=43,max_length=43)
class PrivacyIn(BaseModel):
    discoverable:bool
class ReadIn(BaseModel):
    ids:list[str]=Field(max_length=100)

def allows_contact(db,ident):
    row=db.execute('SELECT discoverable FROM account_privacy WHERE account_id=?',(ident,)).fetchone()
    return not row or bool(row['discoverable'])

def audit_request(store,request,status,stamp):
    # Logs route template and validated opaque resource IDs only, never headers or body.
    route=getattr(request.scope.get('route'),'path','unknown')
    resource=','.join(v for v in request.path_params.values() if isinstance(v,str) and len(v)==32 and all(ch in '0123456789abcdef' for ch in v))
    try:
        with store.transaction(write=True) as db:
            db.execute('INSERT INTO admin_audit(at,method,route,resource,status,actor) VALUES(?,?,?,?,?,?)',(stamp,request.method,route,resource,status,getattr(request.state,'admin_actor','legacy')))
            db.execute('DELETE FROM admin_audit WHERE at<?',(stamp-90*86400,))
    except sqlite3.Error:
        # Do not misreport an already committed mutation as failed.
        log.error('Administrative audit write failed')

def inbox(db,u,stamp):
    ident=u['id']; out=[]
    def add(key,title,body,at):
        out.append({'id':key,'title':title,'body':body,'at':at})
    add('admission:'+u['status'],'Статус допуска',{'APPROVED':'Анкета одобрена','REJECTED':'Анкета отклонена'}.get(u['status'],'Анкета ожидает проверки'),u['created_at'])
    for row in db.execute("""SELECT m.id,m.status,m.deadline,m.created_at FROM matches m JOIN match_players p ON p.match_id=m.id
        WHERE p.account_id=? AND (m.status IN ('ACTIVE','DISPUTED') OR (m.status='READY_CHECK' AND m.deadline>?))""",(ident,stamp)):
        add('match:'+row['id']+':'+row['status'],'Матч',{'READY_CHECK':'Матч найден. Подтверди готовность','ACTIVE':'Матч подтверждён','DISPUTED':'Результат передан модератору'}[row['status']],row['created_at'])
    for row in db.execute("""SELECT r.match_id,r.winner,r.resolved_at,p.side FROM match_results r JOIN match_players p ON p.match_id=r.match_id
        WHERE p.account_id=? ORDER BY r.resolved_at DESC LIMIT 20""",(ident,)):
        add('result:'+row['match_id'],'Результат матча','Аннулирован' if row['winner']=='VOID' else ('Победа' if row['winner']==row['side'] else 'Поражение'),row['resolved_at'])
    for row in db.execute("""SELECT f.a,f.b,f.created_at,p.riot_id FROM friendships f JOIN accounts p ON p.id=f.requester
        WHERE (f.a=? OR f.b=?) AND f.requester!=? AND f.status='PENDING' AND p.status='APPROVED' AND p.region=?""",(ident,ident,ident,u['region'])):
        add('friend:'+row['a']+':'+row['b']+':'+str(row['created_at']),'Запрос дружбы',row['riot_id'],row['created_at'])
    for row in db.execute("""SELECT i.id,i.created_at,s.title FROM session_invites i JOIN sessions s ON s.id=i.session_id
        JOIN accounts a ON a.id=i.sender JOIN friendships f ON (f.a=i.sender AND f.b=i.recipient OR f.b=i.sender AND f.a=i.recipient)
        WHERE i.recipient=? AND i.status='PENDING' AND s.cancelled=0 AND s.ends_at>? AND s.region=? AND a.status='APPROVED' AND f.status='ACCEPTED'""",(ident,stamp,u['region'])):
        add('session:'+row['id'],'Приглашение на сессию',row['title'],row['created_at'])
    for row in db.execute("""SELECT i.id,i.created_at,t.name FROM club_invites i JOIN club_teams t ON t.id=i.team_id
        WHERE i.recipient=? AND t.archived=0 AND t.region=? AND i.created_at>=?""",(ident,u['region'],stamp-7*86400)):
        add('club:'+row['id'],'Приглашение в команду',row['name'],row['created_at'])
    for row in db.execute("SELECT id,status,reviewed_at FROM complaints WHERE reporter=? AND status!='PENDING' ORDER BY reviewed_at DESC LIMIT 20",(ident,)):
        add('complaint:'+row['id'],'Решение по жалобе','Подтверждена' if row['status']=='UPHELD' else 'Отклонена',row['reviewed_at'])
    out=sorted(out,key=lambda x:(x['at'],x['id']),reverse=True)[:100]
    read={r['event_id'] for r in db.execute('SELECT event_id FROM inbox_reads WHERE account_id=?',(ident,))}
    for item in out: item['read']=item['id'] in read
    return out

def install_operations(app,store,token_header,admin_auth,limit,get_user,now,account_dict):
    with store.transaction(write=True) as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS recovery_codes(account_id TEXT PRIMARY KEY REFERENCES accounts(id), digest TEXT UNIQUE NOT NULL);
        CREATE TABLE IF NOT EXISTS account_privacy(account_id TEXT PRIMARY KEY REFERENCES accounts(id), discoverable INTEGER NOT NULL CHECK(discoverable IN (0,1)));
        CREATE TABLE IF NOT EXISTS inbox_reads(account_id TEXT NOT NULL REFERENCES accounts(id),event_id TEXT NOT NULL,read_at INTEGER NOT NULL,PRIMARY KEY(account_id,event_id));
        CREATE TABLE IF NOT EXISTS admin_audit(id INTEGER PRIMARY KEY,at INTEGER NOT NULL,method TEXT NOT NULL,route TEXT NOT NULL,resource TEXT NOT NULL,status INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS admin_audit_at ON admin_audit(at);
        """)
        if "actor" not in {row["name"] for row in db.execute("PRAGMA table_info(admin_audit)")}:
            db.execute("ALTER TABLE admin_audit ADD COLUMN actor TEXT NOT NULL DEFAULT 'legacy'")

    @app.post('/v1/me/recovery')
    def issue_recovery(token=Depends(token_header)):
        with store.transaction() as db: ident=get_user(db,token)['id']
        limit('recovery_issue',ident,5,3600)
        code=secrets.token_urlsafe(32)
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            db.execute('INSERT INTO recovery_codes VALUES(?,?) ON CONFLICT(account_id) DO UPDATE SET digest=excluded.digest',(user['id'],digest(code)))
        return {'code':code}

    @app.post('/v1/recover')
    def recover(payload:RecoverIn,request:Request):
        limit('recover',request.client.host if request.client else 'unknown',10,3600)
        token=secrets.token_urlsafe(32)
        with store.transaction(write=True) as db:
            row=db.execute('SELECT account_id FROM recovery_codes WHERE digest=?',(digest(payload.code),)).fetchone()
            if not row: raise HTTPException(401,'Код недействителен или уже использован')
            ident=row['account_id']
            db.execute('DELETE FROM recovery_codes WHERE account_id=?',(ident,))
            db.execute('UPDATE accounts SET token_hash=? WHERE id=?',(digest(token),ident))
            return {'token':token,'account':account_dict(db,db.execute('SELECT * FROM accounts WHERE id=?',(ident,)).fetchone())}

    @app.get('/v1/me/privacy')
    def privacy(token=Depends(token_header)):
        with store.transaction() as db: return {'discoverable':allows_contact(db,get_user(db,token)['id'])}

    @app.put('/v1/me/privacy')
    def change_privacy(payload:PrivacyIn,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            ident=get_user(db,token)['id']
            db.execute('INSERT INTO account_privacy VALUES(?,?) ON CONFLICT(account_id) DO UPDATE SET discoverable=excluded.discoverable',(ident,int(payload.discoverable)))
            return {'discoverable':payload.discoverable}

    @app.get('/v1/me/inbox')
    def notifications(token=Depends(token_header)):
        with store.transaction() as db:
            items=inbox(db,get_user(db,token),now())
            return {'items':items,'unread':sum(not x['read'] for x in items)}

    @app.post('/v1/me/inbox/read')
    def read_notifications(payload:ReadIn,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            u=get_user(db,token); stamp=now()
            existing={i['id'] for i in inbox(db,u,stamp)}
            for event in set(payload.ids)&existing:
                db.execute('INSERT OR IGNORE INTO inbox_reads VALUES(?,?,?)',(u['id'],event,stamp))
            db.execute('DELETE FROM inbox_reads WHERE account_id=? AND read_at<?',(u['id'],stamp-90*86400))
            return {'status':'OK'}

    @app.get('/readyz')
    def ready():
        try:
            with store.transaction() as db: db.execute('SELECT count(*) FROM accounts').fetchone()
        except sqlite3.Error: raise HTTPException(503,'Database unavailable')
        return {'status':'ready'}

    @app.get('/v1/admin/operations',dependencies=[Depends(admin_auth)])
    def operations():
        with store.transaction() as db:
            counts={}
            for table in ('accounts','queue','matches','club_teams','complaints'):
                counts[table]=db.execute('SELECT count(*) FROM '+table).fetchone()[0]
            audits=[dict(r) for r in db.execute('SELECT at,method,route,resource,status,actor FROM admin_audit ORDER BY id DESC LIMIT 100')]
            return {'database':'ready','counts':counts,'audit':audits,'performance':app.state.performance.snapshot()}
