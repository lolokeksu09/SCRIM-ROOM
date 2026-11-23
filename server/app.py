"""SCRIM ROOM private custom-match coordinator API (pilot, one worker).

Official Riot account ownership and rank cannot be verified with this server.
A moderator must review voluntarily uploaded evidence before approving any player.
"""
from __future__ import annotations

import hashlib
from itertools import combinations
import hmac
import io
import os
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from typing import Literal

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field, field_validator, model_validator
from PIL import Image, ImageOps, UnidentifiedImageError

from server.matchmaking import ROLES, arrange

RANK_VALUES = {'BELOW_MASTER': 0, 'MASTER': 1, 'GRANDMASTER': 2, 'CHALLENGER': 3, 'SOVEREIGN': 4}
RANK_SEEDS = {'GRANDMASTER': 1600, 'CHALLENGER': 1800, 'SOVEREIGN': 2000}
MAX_IMAGE_BYTES = 4 * 1024 * 1024
MAX_EVIDENCE = 4
MAX_PIXELS = 16_000_000


def now_ts() -> int:
    return int(time.time())


class ProfileIn(BaseModel):
    riot_id: str = Field(min_length=5, max_length=64)
    region: Literal['EUROPE', 'ASIA', 'AMERICAS']
    current_rank: Literal['BELOW_MASTER', 'MASTER', 'GRANDMASTER', 'CHALLENGER', 'SOVEREIGN']
    previous_rank: Literal['BELOW_MASTER', 'MASTER', 'GRANDMASTER', 'CHALLENGER', 'SOVEREIGN']
    primary: Literal['BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT']
    secondary: Literal['BARON', 'JUNGLE', 'MID', 'DRAGON', 'SUPPORT']

    @field_validator('riot_id')
    @classmethod
    def valid_riot_id(cls, v):
        v = v.strip()
        pieces = v.split('#')
        if len(pieces) != 2 or not all(p.strip() for p in pieces):
            raise ValueError('Укажи Riot ID в формате Имя#Тег')
        return v

    @field_validator('region')
    @classmethod
    def valid_region(cls, v):
        v = v.strip().upper()
        if not v:
            raise ValueError('Укажи игровой регион')
        return v

    @model_validator(mode='after')
    def valid_roles_and_rank(self):
        if self.primary == self.secondary:
            raise ValueError('Две роли должны отличаться')
        if max(RANK_VALUES[self.current_rank], RANK_VALUES[self.previous_rank]) < 2:
            raise ValueError('Для заявки нужен Грандмастер+ в текущем либо предыдущем сезоне')
        return self


class Database:
    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()  # single-process pilot; workers=1 required
        self.initialize()

    @contextmanager
    def transaction(self, write=False):
        with self.lock:
            con = sqlite3.connect(self.path, timeout=15, isolation_level=None)
            con.row_factory = sqlite3.Row
            con.execute('PRAGMA foreign_keys=ON')
            try:
                con.execute('BEGIN IMMEDIATE' if write else 'BEGIN')
                yield con
                if con.in_transaction:
                    con.execute('COMMIT')
            except Exception:
                if con.in_transaction:
                    con.execute('ROLLBACK')
                raise
            finally:
                con.close()

    def initialize(self):
        with self.transaction(write=True) as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS accounts(
                  id TEXT PRIMARY KEY, riot_id TEXT NOT NULL, region TEXT NOT NULL,
                  current_rank TEXT NOT NULL, previous_rank TEXT NOT NULL,
                  primary_role TEXT NOT NULL, secondary_role TEXT NOT NULL,
                  token_hash TEXT NOT NULL UNIQUE, status TEXT NOT NULL,
                  rating INTEGER NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS unique_riot ON accounts(lower(riot_id),lower(region));
                CREATE TABLE IF NOT EXISTS evidence(
                  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                  slot INTEGER NOT NULL, content BLOB NOT NULL, sha256 TEXT NOT NULL,
                  PRIMARY KEY(account_id,slot), UNIQUE(account_id,sha256)
                );
                CREATE TABLE IF NOT EXISTS queue(
                  account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
                  region TEXT NOT NULL, joined_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS queue_region_time ON queue(region,joined_at);
                CREATE TABLE IF NOT EXISTS matches(
                  id TEXT PRIMARY KEY, region TEXT NOT NULL,
                  status TEXT NOT NULL, created_at INTEGER NOT NULL, deadline INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS match_players(
                  match_id TEXT NOT NULL REFERENCES matches(id),
                  account_id TEXT NOT NULL REFERENCES accounts(id),
                  side TEXT NOT NULL, role TEXT NOT NULL, ready INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY(match_id,account_id), UNIQUE(match_id,side,role)
                );
            ''')
            # executescript commits the surrounding transaction on some SQLite versions.
            # Commit is harmless when the transaction remains open; otherwise skip it.


def account_dict(db, row):
    count = db.execute('SELECT count(*) FROM evidence WHERE account_id=?', (row['id'],)).fetchone()[0]
    return {
        'id': row['id'], 'riot_id': row['riot_id'], 'region': row['region'],
        'current_rank': row['current_rank'], 'previous_rank': row['previous_rank'],
        'primary': row['primary_role'], 'secondary': row['secondary_role'],
        'status': row['status'], 'rating_seed': row['rating'],
        'evidence_count': count,
    }


def get_user(db, token):
    if not token:
        raise HTTPException(401, 'Необходим токен участника')
    row = db.execute('SELECT * FROM accounts WHERE token_hash=?', (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
    if row is None:
        raise HTTPException(401, 'Неверный токен участника')
    return row


def _match_info(db, match_id):
    match = db.execute('SELECT * FROM matches WHERE id=?', (match_id,)).fetchone()
    if not match:
        return None
    players = db.execute('''
      SELECT mp.side,mp.role,mp.ready,mp.account_id,a.riot_id,a.rating
      FROM match_players mp JOIN accounts a ON a.id=mp.account_id
      WHERE mp.match_id=? ORDER BY mp.side,mp.role
    ''', (match_id,)).fetchall()
    return {
        'id': match['id'], 'status': match['status'], 'region': match['region'],
        'ready_count': sum(x['ready'] for x in players),
        'deadline': match['deadline'],
        'blue': [{'account_id': x['account_id'], 'riot_id': x['riot_id'], 'rating_seed': x['rating'], 'role': x['role'], 'ready': bool(x['ready'])} for x in players if x['side']=='BLUE'],
        'red': [{'account_id': x['account_id'], 'riot_id': x['riot_id'], 'rating_seed': x['rating'], 'role': x['role'], 'ready': bool(x['ready'])} for x in players if x['side']=='RED'],
    }


def _active_match_id(db, account_id):
    row = db.execute('''
      SELECT m.id FROM matches m JOIN match_players mp ON mp.match_id=m.id
      WHERE mp.account_id=? AND m.status IN ('READY_CHECK','ACTIVE')
      ORDER BY m.created_at DESC LIMIT 1
    ''', (account_id,)).fetchone()
    return row['id'] if row else None


def _expire_ready_checks(db):
    expired = db.execute("SELECT id FROM matches WHERE status='READY_CHECK' AND deadline<?", (now_ts(),)).fetchall()
    for item in expired:
        mid = item['id']
        db.execute("UPDATE matches SET status='CANCELLED' WHERE id=?", (mid,))
        # Requeue only players who pressed Ready before deadline.
        players = db.execute('''
          SELECT a.id,a.region FROM match_players mp JOIN accounts a ON a.id=mp.account_id
          WHERE mp.match_id=? AND mp.ready=1 AND a.status='APPROVED'
        ''', (mid,)).fetchall()
        for player in players:
            db.execute('INSERT OR IGNORE INTO queue(account_id,region,joined_at) VALUES(?,?,?)', (player['id'],player['region'],now_ts()))


def _try_match(db, region):
    # Choose earliest feasible ten within the first twelve to reduce role starvation.
    waiters = db.execute('''
      SELECT a.id,a.riot_id,a.region,a.primary_role AS "primary",
      a.secondary_role AS secondary,a.rating
      FROM queue q JOIN accounts a ON a.id=q.account_id
      WHERE q.region=? AND a.status='APPROVED'
      ORDER BY q.joined_at,q.rowid LIMIT 12
    ''', (region,)).fetchall()
    if len(waiters) < 10:
        return
    arranged = None
    for indexes in combinations(range(len(waiters)), 10):
        arranged = arrange([dict(waiters[i]) for i in indexes])
        if arranged is not None:
            break
    if arranged is None:
        return
    blue, red, _ = arranged
    mid = uuid.uuid4().hex
    deadline = now_ts()+180
    db.execute('INSERT INTO matches VALUES(?,?,?,?,?)', (mid,region,'READY_CHECK',now_ts(),deadline))
    for side, team in [('BLUE',blue),('RED',red)]:
        for p, role in team:
            db.execute('INSERT INTO match_players(match_id,account_id,side,role) VALUES(?,?,?,?)', (mid,p['id'],side,role))
            db.execute('DELETE FROM queue WHERE account_id=?', (p['id'],))


def _read_safe_image(content: bytes) -> bytes:
    if not content or len(content) > MAX_IMAGE_BYTES:
        raise HTTPException(413, 'Скриншот должен быть меньше 4 МБ')
    try:
        with Image.open(io.BytesIO(content)) as im:
            if im.width * im.height > MAX_PIXELS or im.width < 8 or im.height < 8:
                raise HTTPException(422, 'Неподдерживаемые размеры изображения')
            if im.format not in ('PNG', 'JPEG', 'WEBP'):
                raise HTTPException(422, 'Разрешены только PNG, JPEG и WEBP')
            # Re-encoding strips metadata and prevents arbitrary original bytes from serving.
            rgb = ImageOps.exif_transpose(im).convert('RGB')
            out = io.BytesIO()
            rgb.save(out, 'JPEG', quality=85, optimize=True)
            return out.getvalue()
    except (OSError, UnidentifiedImageError, ValueError):
        raise HTTPException(422, 'Файл не является изображением') from None


def create_app(db_path: str, admin_key: str | None = None):
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
    store = Database(db_path)
    admin_key = admin_key or ''
    app = FastAPI(title='SCRIM ROOM Private Match API', version='0.2.0', docs_url=None, redoc_url=None)

    def token_header(authorization: str | None = Header(default=None)):
        if not authorization or not authorization.startswith('Bearer '):
            raise HTTPException(401, 'Необходим токен участника')
        return authorization[7:]

    def admin_auth(x_admin_key: str | None = Header(default=None)):
        if not admin_key or len(admin_key) < 32 or not x_admin_key or not hmac.compare_digest(x_admin_key, admin_key):
            raise HTTPException(403, 'Нет доступа администратора')

    @app.get('/healthz')
    def healthz():
        return {'status':'ok'}

    @app.post('/v1/accounts', status_code=201)
    def create_account(payload: ProfileIn):
        token = secrets.token_urlsafe(32)
        ident = uuid.uuid4().hex
        high_rank = max((payload.current_rank,payload.previous_rank), key=lambda v:RANK_VALUES[v])
        rating = RANK_SEEDS[high_rank]
        with store.transaction(write=True) as db:
            try:
                db.execute('''INSERT INTO accounts VALUES(?,?,?,?,?,?,?,?,?,?,?)''', (
                    ident,payload.riot_id,payload.region,payload.current_rank,payload.previous_rank,
                    payload.primary,payload.secondary,hashlib.sha256(token.encode()).hexdigest(),
                    'PENDING_REVIEW',rating,now_ts()))
            except sqlite3.IntegrityError:
                raise HTTPException(409, 'Такой Riot ID уже зарегистрирован в этом регионе')
            row = db.execute('SELECT * FROM accounts WHERE id=?', (ident,)).fetchone()
            return {'token':token, 'account':account_dict(db,row)}

    @app.get('/v1/me')
    def me(token=Depends(token_header)):
        with store.transaction() as db:
            return account_dict(db,get_user(db,token))

    @app.put('/v1/me')
    def update_me(payload: ProfileIn,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            if _active_match_id(db,user['id']):
                raise HTTPException(409, 'Заверши текущий матч перед изменением анкеты')
            high_rank=max((payload.current_rank,payload.previous_rank), key=lambda v:RANK_VALUES[v])
            db.execute('DELETE FROM queue WHERE account_id=?',(user['id'],))
            db.execute('DELETE FROM evidence WHERE account_id=?',(user['id'],))
            try:
                db.execute('''UPDATE accounts SET riot_id=?,region=?,current_rank=?,previous_rank=?,
                    primary_role=?,secondary_role=?,rating=?,status='PENDING_REVIEW' WHERE id=?''',
                    (payload.riot_id,payload.region,payload.current_rank,payload.previous_rank,
                     payload.primary,payload.secondary,RANK_SEEDS[high_rank],user['id']))
            except sqlite3.IntegrityError:
                raise HTTPException(409, 'Такой Riot ID уже зарегистрирован')
            return account_dict(db,db.execute('SELECT * FROM accounts WHERE id=?',(user['id'],)).fetchone())

    @app.post('/v1/me/evidence')
    async def submit_evidence(image: UploadFile=File(...),token=Depends(token_header)):
        content=await image.read(MAX_IMAGE_BYTES+1)
        safe=_read_safe_image(content)
        hashed=hashlib.sha256(safe).hexdigest()
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            if user['status']=='APPROVED':
                raise HTTPException(409, 'Для изменения документов обнови анкету')
            found=db.execute('SELECT slot FROM evidence WHERE account_id=? AND sha256=?',(user['id'],hashed)).fetchone()
            if found:
                raise HTTPException(409, 'Этот скриншот уже загружен')
            slots={row['slot'] for row in db.execute('SELECT slot FROM evidence WHERE account_id=?',(user['id'],))}
            slot=next((i for i in range(1,MAX_EVIDENCE+1) if i not in slots),None)
            if slot is None:
                raise HTTPException(409, 'Максимум четыре изображения')
            db.execute('INSERT INTO evidence VALUES(?,?,?,?)',(user['id'],slot,safe,hashed))
            return {'slot':slot,'evidence_count':len(slots)+1,'status':'PENDING_REVIEW'}

    @app.get('/v1/queue/state')
    def queue_state(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            _expire_ready_checks(db)
            match_id=_active_match_id(db,user['id'])
            queued=db.execute('SELECT 1 FROM queue WHERE account_id=?',(user['id'],)).fetchone() is not None
            count=db.execute('SELECT count(*) FROM queue WHERE region=?',(user['region'],)).fetchone()[0]
            return {'account_status':user['status'],'queued':queued,'waiting_in_region':count,
                    'match':_match_info(db,match_id) if match_id else None}

    @app.post('/v1/queue/join')
    def join_queue(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            _expire_ready_checks(db)
            if user['status'] != 'APPROVED':
                raise HTTPException(403, 'Требуется одобрение заявки модератором')
            if _active_match_id(db,user['id']):
                raise HTTPException(409, 'Игрок уже назначен на матч')
            db.execute('INSERT OR IGNORE INTO queue(account_id,region,joined_at) VALUES(?,?,?)',
                       (user['id'],user['region'],now_ts()))
            _try_match(db,user['region'])
            match_id=_active_match_id(db,user['id'])
            return {'queued':match_id is None,'match':_match_info(db,match_id) if match_id else None}

    @app.post('/v1/queue/leave')
    def leave_queue(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            db.execute('DELETE FROM queue WHERE account_id=?',(user['id'],))
            return {'queued':False}

    def match_member(db,token,match_id):
        user=get_user(db,token)
        mp=db.execute('SELECT * FROM match_players WHERE match_id=? AND account_id=?',(match_id,user['id'])).fetchone()
        if not mp:
            raise HTTPException(404,'Матч не найден')
        return user,mp

    @app.post('/v1/matches/{match_id}/ready')
    def ready(match_id:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user,mp=match_member(db,token,match_id)
            match=db.execute('SELECT status,deadline FROM matches WHERE id=?',(match_id,)).fetchone()
            if match['status']!='READY_CHECK' or match['deadline']<now_ts():
                _expire_ready_checks(db)
                raise HTTPException(409,'Проверка готовности завершена')
            db.execute('UPDATE match_players SET ready=1 WHERE match_id=? AND account_id=?',(match_id,user['id']))
            count=db.execute('SELECT sum(ready) FROM match_players WHERE match_id=?',(match_id,)).fetchone()[0]
            if count==10:
                db.execute("UPDATE matches SET status='ACTIVE' WHERE id=?",(match_id,))
            return _match_info(db,match_id)

    @app.post('/v1/matches/{match_id}/decline')
    def decline(match_id:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user,_=match_member(db,token,match_id)
            match=db.execute('SELECT * FROM matches WHERE id=?',(match_id,)).fetchone()
            if match['status']!='READY_CHECK':
                raise HTTPException(409,'Отказ доступен только при проверке готовности')
            db.execute("UPDATE matches SET status='CANCELLED' WHERE id=?",(match_id,))
            others=db.execute('''SELECT a.id,a.region FROM match_players mp
                JOIN accounts a ON a.id=mp.account_id
                WHERE mp.match_id=? AND a.id!=? AND a.status='APPROVED' ''',(match_id,user['id'])).fetchall()
            for other in others:
                db.execute('INSERT OR IGNORE INTO queue(account_id,region,joined_at) VALUES(?,?,?)',
                           (other['id'],other['region'],now_ts()))
            return {'status':'CANCELLED'}

    @app.get('/v1/admin/accounts', dependencies=[Depends(admin_auth)])
    def admin_accounts():
        with store.transaction() as db:
            rows=db.execute('SELECT * FROM accounts ORDER BY created_at DESC').fetchall()
            return [account_dict(db,row) for row in rows]

    @app.get('/v1/admin/accounts/{account_id}/evidence/{slot}', dependencies=[Depends(admin_auth)])
    def admin_evidence(account_id:str,slot:int):
        with store.transaction() as db:
            image=db.execute('SELECT content FROM evidence WHERE account_id=? AND slot=?',(account_id,slot)).fetchone()
            if not image:
                raise HTTPException(404,'Скриншот не найден')
            return Response(content=image['content'],media_type='image/jpeg',headers={'Cache-Control':'no-store'})

    @app.post('/v1/admin/accounts/{account_id}/approve', dependencies=[Depends(admin_auth)])
    def admin_approve(account_id:str):
        with store.transaction(write=True) as db:
            user=db.execute('SELECT * FROM accounts WHERE id=?',(account_id,)).fetchone()
            if not user:
                raise HTTPException(404,'Участник не найден')
            evidence=db.execute('SELECT count(*) FROM evidence WHERE account_id=?',(account_id,)).fetchone()[0]
            if evidence<2:
                raise HTTPException(409,'Нужно минимум два подтверждающих скриншота')
            db.execute("UPDATE accounts SET status='APPROVED' WHERE id=?",(account_id,))
            return account_dict(db,db.execute('SELECT * FROM accounts WHERE id=?',(account_id,)).fetchone())

    @app.post('/v1/admin/accounts/{account_id}/reject', dependencies=[Depends(admin_auth)])
    def admin_reject(account_id:str):
        with store.transaction(write=True) as db:
            changed=db.execute("UPDATE accounts SET status='REJECTED' WHERE id=?",(account_id,)).rowcount
            if not changed:
                raise HTTPException(404,'Участник не найден')
            db.execute('DELETE FROM queue WHERE account_id=?',(account_id,))
            return {'status':'REJECTED'}

    @app.post('/v1/admin/matches/{match_id}/close', dependencies=[Depends(admin_auth)])
    def admin_close_match(match_id:str):
        with store.transaction(write=True) as db:
            changed=db.execute("UPDATE matches SET status='CLOSED' WHERE id=? AND status='ACTIVE'",(match_id,)).rowcount
            if not changed:
                raise HTTPException(409,'Активный матч не найден')
            return {'status':'CLOSED'}

    return app


app = create_app(os.environ.get('SCRIM_DB_PATH','/tmp/scrim-room-pilot.db'),
                 admin_key=os.environ.get('SCRIM_ADMIN_KEY'))
