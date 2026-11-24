"""SCRIM ROOM private custom-match coordinator API (pilot, one worker).

Official Riot account ownership and rank cannot be verified with this server.
A moderator must review voluntarily uploaded evidence before approving any player.
"""
from __future__ import annotations

import hashlib
import asyncio
import logging
import hmac
import io
import os
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager, asynccontextmanager
from typing import Literal
from pathlib import Path

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile, Request
from fastapi.responses import Response, FileResponse, JSONResponse
from fastapi.exceptions import RequestValidationError
from server.request_guard import RequestBodyLimit
from pydantic import BaseModel, Field, field_validator, model_validator
from PIL import Image, ImageOps, UnidentifiedImageError

from server.matchmaking import ROLES, choose_match, balance_metrics
from server.match_intelligence import enrich_candidates, save_quality, load_quality
from server.ocr import recognize_stats, OcrUnavailable

RANK_VALUES = {'BELOW_MASTER': 0, 'MASTER': 1, 'GRANDMASTER': 2, 'CHALLENGER': 3, 'SOVEREIGN': 4}
RANK_SEEDS = {'GRANDMASTER': 1600, 'CHALLENGER': 1800, 'SOVEREIGN': 2000}
MAX_IMAGE_BYTES = 4 * 1024 * 1024
MAX_EVIDENCE = 4
MAX_PIXELS = 16_000_000
QUEUE_IDLE_SECONDS = 90
ACTIVE_TIMEOUT_SECONDS = 2 * 3600
DISPUTE_TIMEOUT_SECONDS = 48 * 3600
MAINTENANCE_INTERVAL_SECONDS = 10
log = logging.getLogger(__name__)


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


class ResultIn(BaseModel):
    winner: Literal['BLUE', 'RED']


class ResolutionIn(BaseModel):
    winner: Literal['BLUE', 'RED', 'VOID']


class SessionIn(BaseModel):
    title: str = Field(min_length=3,max_length=80)
    region: Literal['EUROPE','ASIA','AMERICAS']
    starts_at: int
    ends_at: int
    capacity: int = Field(ge=10,le=100)

    @model_validator(mode='after')
    def validate_window(self):
        self.title=self.title.strip()
        if len(self.title)<3 or self.starts_at<now_ts() or not 600<=self.ends_at-self.starts_at<=7200 or self.starts_at>now_ts()+30*86400:
            raise ValueError('Сессия: будущая дата в пределах 30 дней, длительность 10–120 минут')
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
                CREATE TABLE IF NOT EXISTS queue_presence(
                  account_id TEXT PRIMARY KEY REFERENCES queue(account_id) ON DELETE CASCADE,
                  last_seen INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS queue_presence_time ON queue_presence(last_seen);
                CREATE INDEX IF NOT EXISTS queue_region_time ON queue(region,joined_at);
                CREATE TABLE IF NOT EXISTS matches(
                  id TEXT PRIMARY KEY, region TEXT NOT NULL,
                  status TEXT NOT NULL, created_at INTEGER NOT NULL, deadline INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS match_phase_deadlines(
                  match_id TEXT PRIMARY KEY REFERENCES matches(id), expires_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS match_phase_expiry ON match_phase_deadlines(expires_at);
                CREATE TABLE IF NOT EXISTS match_players(
                  match_id TEXT NOT NULL REFERENCES matches(id),
                  account_id TEXT NOT NULL REFERENCES accounts(id),
                  side TEXT NOT NULL, role TEXT NOT NULL, ready INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY(match_id,account_id), UNIQUE(match_id,side,role)
                );
                CREATE INDEX IF NOT EXISTS match_players_account ON match_players(account_id,match_id);
                CREATE TABLE IF NOT EXISTS match_quality(
                  match_id TEXT PRIMARY KEY REFERENCES matches(id), snapshot TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS evidence_hash_lookup ON evidence(sha256);
                CREATE TABLE IF NOT EXISTS discipline_events(
                  account_id TEXT NOT NULL REFERENCES accounts(id), match_id TEXT NOT NULL REFERENCES matches(id),
                  reason TEXT NOT NULL, created_at INTEGER NOT NULL, blocked_until INTEGER NOT NULL,
                  PRIMARY KEY(account_id,match_id)
                );
                CREATE INDEX IF NOT EXISTS discipline_account ON discipline_events(account_id,blocked_until);
                CREATE TABLE IF NOT EXISTS request_limits(
                  scope TEXT NOT NULL, identity TEXT NOT NULL, window_start INTEGER NOT NULL,
                  count INTEGER NOT NULL, PRIMARY KEY(scope,identity,window_start)
                );
                CREATE TABLE IF NOT EXISTS sessions(
                  id TEXT PRIMARY KEY,title TEXT NOT NULL,region TEXT NOT NULL,
                  starts_at INTEGER NOT NULL,ends_at INTEGER NOT NULL,capacity INTEGER NOT NULL,cancelled INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS session_signups(
                  session_id TEXT NOT NULL REFERENCES sessions(id),account_id TEXT NOT NULL REFERENCES accounts(id),
                  joined_at INTEGER NOT NULL,PRIMARY KEY(session_id,account_id)
                );
                CREATE TABLE IF NOT EXISTS queue_sessions(
                  account_id TEXT PRIMARY KEY REFERENCES queue(account_id) ON DELETE CASCADE,
                  session_id TEXT NOT NULL REFERENCES sessions(id)
                );
                CREATE TABLE IF NOT EXISTS match_sessions(
                  match_id TEXT PRIMARY KEY REFERENCES matches(id),session_id TEXT NOT NULL REFERENCES sessions(id)
                );
                CREATE INDEX IF NOT EXISTS session_region_start ON sessions(region,starts_at);
                CREATE TABLE IF NOT EXISTS match_reports(
                  match_id TEXT NOT NULL REFERENCES matches(id), side TEXT NOT NULL,
                  winner TEXT NOT NULL, reported_at INTEGER NOT NULL,
                  PRIMARY KEY(match_id,side)
                );
                CREATE TABLE IF NOT EXISTS match_results(
                  match_id TEXT PRIMARY KEY REFERENCES matches(id), winner TEXT NOT NULL,
                  source TEXT NOT NULL, resolved_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS rating_changes(
                  match_id TEXT NOT NULL REFERENCES match_results(match_id),
                  account_id TEXT NOT NULL REFERENCES accounts(id), before_rating INTEGER NOT NULL,
                  after_rating INTEGER NOT NULL, PRIMARY KEY(match_id,account_id)
                );
            ''')
            # executescript commits the surrounding transaction on some SQLite versions.
            # Commit is harmless when the transaction remains open; otherwise skip it.


def discipline(db, account_id):
    row=db.execute('SELECT count(*) AS n,coalesce(max(blocked_until),0) AS until FROM discipline_events WHERE account_id=?',(account_id,)).fetchone()
    return {'incidents':row['n'],'blocked_until':row['until'] if row['until']>now_ts() else 0}


def add_incident(db, account_id, match_id, reason):
    duration = 60 if reason=='DECLINED' else 120
    stamp=now_ts()
    db.execute('INSERT OR IGNORE INTO discipline_events VALUES(?,?,?,?,?)',(account_id,match_id,reason,stamp,stamp+duration))


def duplicate_slots(db, account_id):
    rows=db.execute('''SELECT DISTINCT e.slot FROM evidence e WHERE e.account_id=?
        AND EXISTS(SELECT 1 FROM evidence x WHERE x.sha256=e.sha256 AND x.account_id!=e.account_id)
        ORDER BY e.slot''',(account_id,)).fetchall()
    return [r['slot'] for r in rows]


def account_dict(db, row):
    count = db.execute('SELECT count(*) FROM evidence WHERE account_id=?', (row['id'],)).fetchone()[0]
    return {
        'id': row['id'], 'riot_id': row['riot_id'], 'region': row['region'],
        'current_rank': row['current_rank'], 'previous_rank': row['previous_rank'],
        'primary': row['primary_role'], 'secondary': row['secondary_role'],
        'status': row['status'], 'rating_seed': row['rating'],
        'evidence_count': count, 'discipline': discipline(db,row['id']),
    }


def review_revision(db, row):
    # Bind moderation to the profile and evidence actually reviewed.
    evidence = db.execute('SELECT slot,sha256 FROM evidence WHERE account_id=? ORDER BY slot', (row['id'],)).fetchall()
    fields = [row[k] for k in ('id','riot_id','region','current_rank','previous_rank','primary_role','secondary_role','status')]
    fields.extend(f"{item['slot']}:{item['sha256']}" for item in evidence)
    return hashlib.sha256(repr(fields).encode()).hexdigest()


def get_user(db, token):
    if not token:
        raise HTTPException(401, 'Необходим токен участника')
    row = db.execute('SELECT * FROM accounts WHERE token_hash=?', (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
    if row is None:
        raise HTTPException(401, 'Неверный токен участника')
    return row


def _match_info(db, match_id, viewer_id=None, moderator=False):
    match = db.execute('SELECT * FROM matches WHERE id=?', (match_id,)).fetchone()
    if not match:
        return None
    players = db.execute('''
      SELECT mp.side,mp.role,mp.ready,mp.account_id,a.riot_id,a.rating
      FROM match_players mp JOIN accounts a ON a.id=mp.account_id
      WHERE mp.match_id=? ORDER BY mp.side,mp.role
    ''', (match_id,)).fetchall()
    reports = db.execute('SELECT side,winner FROM match_reports WHERE match_id=?', (match_id,)).fetchall()
    result = db.execute('SELECT winner,source,resolved_at FROM match_results WHERE match_id=?', (match_id,)).fetchone()
    own = next((p for p in players if p['account_id']==viewer_id), None)
    captain = own is not None and own['role']=='BARON'
    own_report = next((r['winner'] for r in reports if captain and r['side']==own['side']), None)
    from server.match_experience import match_details
    extra = match_details(db,match,own,moderator,now_ts())
    return {
        'id': match['id'], 'status': match['status'], 'region': match['region'],
        'session_id': (lambda r:r['session_id'] if r else None)(db.execute('SELECT session_id FROM match_sessions WHERE match_id=?',(match_id,)).fetchone()),
        'is_captain': captain, 'own_report': own_report, 'report_count': len(reports),
        **extra,
        'quality': load_quality(db,match_id,full=moderator) if own is not None or moderator else None,
        'reports': {r['side']:r['winner'] for r in reports} if moderator else {},
        'result': dict(result) if result else None,
        'ready_count': sum(x['ready'] for x in players),
        'deadline': match['deadline'],
        'phase_expires_at': phase_deadline(db,match),
        'blue': [{'account_id': x['account_id'], 'riot_id': x['riot_id'], 'rating_seed': x['rating'], 'role': x['role'], 'ready': bool(x['ready'])} for x in players if x['side']=='BLUE'],
        'red': [{'account_id': x['account_id'], 'riot_id': x['riot_id'], 'rating_seed': x['rating'], 'role': x['role'], 'ready': bool(x['ready'])} for x in players if x['side']=='RED'],
    }


def _active_match_id(db, account_id):
    row = db.execute('''
      SELECT m.id FROM matches m JOIN match_players mp ON mp.match_id=m.id
      WHERE mp.account_id=? AND m.status IN ('READY_CHECK','ACTIVE','DISPUTED')
      ORDER BY m.created_at DESC LIMIT 1
    ''', (account_id,)).fetchone()
    return row['id'] if row else None


def _finish_result(db, mid, winner, source):
    if db.execute('SELECT 1 FROM match_results WHERE match_id=?',(mid,)).fetchone():
        raise HTTPException(409,'Результат уже сохранён')
    players=db.execute('SELECT mp.account_id,mp.side,a.rating FROM match_players mp JOIN accounts a ON a.id=mp.account_id WHERE mp.match_id=?',(mid,)).fetchall()
    if len(players)!=10 or any(sum(p['side']==side for p in players)!=5 for side in ('BLUE','RED')):
        raise HTTPException(409,'Неполный состав матча')
    db.execute('INSERT INTO match_results VALUES(?,?,?,?)',(mid,winner,source,now_ts()))
    if winner!='VOID':
        blue=sum(p['rating'] for p in players if p['side']=='BLUE')/5
        red=sum(p['rating'] for p in players if p['side']=='RED')/5
        p_blue=1/(1+10**max(-20,min(20,(red-blue)/400)))
        delta=round(24*((1 if winner=='BLUE' else 0)-p_blue))
        for p in players:
            after=p['rating']+(delta if p['side']=='BLUE' else -delta)
            db.execute('UPDATE accounts SET rating=? WHERE id=?',(after,p['account_id']))
            db.execute('INSERT INTO rating_changes VALUES(?,?,?,?)',(mid,p['account_id'],p['rating'],after))
    db.execute("UPDATE matches SET status='CLOSED' WHERE id=?",(mid,))
    db.execute('DELETE FROM match_phase_deadlines WHERE match_id=?',(mid,))


def phase_deadline(db,match):
    if match['status']=='READY_CHECK': return match['deadline']
    if match['status'] not in ('ACTIVE','DISPUTED'): return None
    row=db.execute('SELECT expires_at FROM match_phase_deadlines WHERE match_id=?',(match['id'],)).fetchone()
    if row:return row['expires_at']
    # Backward-compatible fallback for matches assigned before RC.3.
    if match['status']=='ACTIVE': return match['deadline']+ACTIVE_TIMEOUT_SECONDS
    last=db.execute('SELECT max(reported_at) AS last FROM match_reports WHERE match_id=?',(match['id'],)).fetchone()['last']
    return (last or match['deadline'])+DISPUTE_TIMEOUT_SECONDS


def set_phase_deadline(db,mid,seconds):
    db.execute('INSERT INTO match_phase_deadlines VALUES(?,?) ON CONFLICT(match_id) DO UPDATE SET expires_at=excluded.expires_at',(mid,now_ts()+seconds))


def expire_unresolved_matches(db):
    for match in db.execute("SELECT * FROM matches WHERE status IN ('ACTIVE','DISPUTED')").fetchall():
        if phase_deadline(db,match)<=now_ts():
            _finish_result(db,match['id'],'VOID','TIMEOUT')


def session_status(row):
    if row['cancelled']: return 'CANCELLED'
    if now_ts()>=row['ends_at']: return 'ENDED'
    if now_ts()>=row['starts_at']: return 'OPEN'
    return 'UPCOMING'


def session_info(db,row,viewer=None):
    sid=row['id']
    counts=db.execute("""SELECT a.primary_role,count(*) AS n FROM session_signups s
        JOIN accounts a ON a.id=s.account_id WHERE s.session_id=? AND a.status='APPROVED' AND a.region=?
        GROUP BY a.primary_role""",(sid,row['region'])).fetchall()
    roles={r['primary_role']:r['n'] for r in counts}
    return dict(row,status=session_status(row),registered=sum(roles.values()),roles=roles,
        enrolled=bool(viewer and db.execute('SELECT 1 FROM session_signups WHERE session_id=? AND account_id=?',(sid,viewer)).fetchone()),
        waiting=db.execute('SELECT count(*) FROM queue_sessions WHERE session_id=?',(sid,)).fetchone()[0])


def touch_queue(db, ident):
    db.execute('''INSERT INTO queue_presence(account_id,last_seen)
      SELECT account_id,? FROM queue WHERE account_id=?
      ON CONFLICT(account_id) DO UPDATE SET last_seen=excluded.last_seen''',(now_ts(),ident))


def clean_session_queues(db):
    # Existing pre-RC queue records fall back to joined_at. Expiration never penalizes a player.
    db.execute('''DELETE FROM queue WHERE account_id IN (
       SELECT q.account_id FROM queue q LEFT JOIN queue_presence p ON p.account_id=q.account_id
       WHERE coalesce(p.last_seen,q.joined_at)<=?)''',(now_ts()-QUEUE_IDLE_SECONDS,))
    db.execute("""DELETE FROM queue WHERE account_id IN (
       SELECT q.account_id FROM queue_sessions q JOIN sessions s ON s.id=q.session_id
       WHERE s.cancelled=1 OR s.ends_at<=?)""",(now_ts(),))


def requeue_match_player(db,mid,player):
    scope=db.execute('SELECT session_id FROM match_sessions WHERE match_id=?',(mid,)).fetchone()
    if scope:
        sid=scope['session_id']
        event=db.execute('SELECT * FROM sessions WHERE id=?',(sid,)).fetchone()
        if session_status(event)!='OPEN' or not db.execute('SELECT 1 FROM session_signups WHERE session_id=? AND account_id=?',(sid,player['id'])).fetchone():
            return
    else:
        sid=None
    if discipline(db,player['id'])['blocked_until']: return
    inserted=db.execute('INSERT OR IGNORE INTO queue(account_id,region,joined_at) VALUES(?,?,?)',(player['id'],player['region'],now_ts())).rowcount
    if inserted:
        touch_queue(db,player['id'])
        if sid: db.execute('INSERT INTO queue_sessions VALUES(?,?)',(player['id'],sid))


def _expire_ready_checks(db):
    expired = db.execute("SELECT id FROM matches WHERE status='READY_CHECK' AND deadline<=?", (now_ts(),)).fetchall()
    for item in expired:
        mid = item['id']
        db.execute("UPDATE matches SET status='CANCELLED' WHERE id=?", (mid,))
        for missing in db.execute('SELECT account_id FROM match_players WHERE match_id=? AND ready=0',(mid,)).fetchall():
            add_incident(db,missing['account_id'],mid,'NO_READY')
        # Requeue only players who pressed Ready before deadline.
        players = db.execute('''
          SELECT a.id,a.region FROM match_players mp JOIN accounts a ON a.id=mp.account_id
          WHERE mp.match_id=? AND mp.ready=1 AND a.status='APPROVED'
        ''', (mid,)).fetchall()
        for player in players:
            requeue_match_player(db,mid,player)


def _try_match(db, region, session_id=None):
    # Role coverage spans the whole live regional/session queue.
    waiters = db.execute('''
      SELECT a.id,a.riot_id,a.region,a.primary_role AS "primary",
      a.secondary_role AS secondary,a.rating,q.joined_at
      FROM queue q JOIN accounts a ON a.id=q.account_id LEFT JOIN queue_sessions qs ON qs.account_id=q.account_id
      WHERE q.region=? AND a.status='APPROVED' AND qs.session_id IS ?
      ORDER BY q.joined_at,q.rowid
    ''', (region,session_id)).fetchall()
    if len(waiters) < 10:
        return
    candidates, repeats = enrich_candidates(db,[dict(w) for w in waiters],now_ts())
    picked=choose_match(candidates,repeats,now_ts())
    if picked is None:
        return
    arranged,selected_wait=picked
    blue, red, _ = arranged
    quality=balance_metrics(blue,red,repeats,selected_wait)
    mid = uuid.uuid4().hex
    deadline = now_ts()+180
    db.execute('INSERT INTO matches VALUES(?,?,?,?,?)', (mid,region,'READY_CHECK',now_ts(),deadline))
    if session_id: db.execute('INSERT INTO match_sessions VALUES(?,?)',(mid,session_id))
    save_quality(db,mid,quality)
    for side, team in [('BLUE',blue),('RED',red)]:
        for p, role in team:
            db.execute('INSERT INTO match_players(match_id,account_id,side,role) VALUES(?,?,?,?)', (mid,p['id'],side,role))
            db.execute('DELETE FROM queue WHERE account_id=?', (p['id'],))


def maintenance_tick(store):
    """One transactional maintenance pass, independent of incoming clients."""
    with store.transaction(write=True) as db:
        _expire_ready_checks(db)
        expire_unresolved_matches(db)
        clean_session_queues(db)
        scopes=db.execute("""SELECT q.region,qs.session_id FROM queue q
          LEFT JOIN queue_sessions qs ON qs.account_id=q.account_id
          GROUP BY q.region,qs.session_id HAVING count(*)>=10""").fetchall()
        for scope in scopes:
            _try_match(db,scope['region'],scope['session_id'])


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


def create_app(db_path: str, admin_key: str | None = None, registration_open: bool = True):
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
    store = Database(db_path)
    admin_key = admin_key or ''
    @asynccontextmanager
    async def lifespan(app):
        async def loop():
            while True:
                await asyncio.sleep(MAINTENANCE_INTERVAL_SECONDS)
                try:
                    await asyncio.to_thread(maintenance_tick,store)
                except Exception:
                    log.exception('Match maintenance pass failed')
        task=asyncio.create_task(loop())
        try:
            yield
        finally:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
    app = FastAPI(title='SCRIM ROOM Private Match API', version='1.0.0-rc.3', docs_url=None, redoc_url=None, openapi_url=None,lifespan=lifespan)
    app.add_middleware(RequestBodyLimit)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        # Do not reflect submitted secrets or arbitrary user payloads in error bodies.
        return JSONResponse({'detail':'Некорректные данные запроса'},status_code=422,headers={'Cache-Control':'no-store'})

    def limit(scope, identity, maximum, period):
        stamp=now_ts()
        start=stamp//period*period
        digest=hashlib.sha256(identity.encode()).hexdigest()
        with store.transaction(write=True) as db:
            db.execute('DELETE FROM request_limits WHERE scope=? AND window_start<?',(scope,start))
            db.execute('INSERT INTO request_limits VALUES(?,?,?,1) ON CONFLICT(scope,identity,window_start) DO UPDATE SET count=count+1',(scope,digest,start))
            count=db.execute('SELECT count FROM request_limits WHERE scope=? AND identity=? AND window_start=?',(scope,digest,start)).fetchone()[0]
        if count>maximum:
            raise HTTPException(429,'Слишком много запросов. Попробуй позже.',headers={'Retry-After':str(start+period-stamp)})

    def token_header(authorization: str | None = Header(default=None)):
        if not authorization or not authorization.startswith('Bearer '):
            raise HTTPException(401, 'Необходим токен участника')
        return authorization[7:]

    def admin_auth(request: Request,x_admin_key: str | None = Header(default=None)):
        if not admin_key or len(admin_key) < 32 or not x_admin_key or not hmac.compare_digest(x_admin_key.encode('utf-8'), admin_key.encode('utf-8')):
            raise HTTPException(403, 'Нет доступа администратора')
        request.state.admin_authorized=True

    admin_dir = Path(__file__).resolve().parent / 'admin'

    @app.middleware('http')
    async def protect_admin_responses(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith('/v1/'):
            response.headers['Cache-Control']='no-store'
        if getattr(request.state,'admin_authorized',False) and request.method in ('POST','PUT','PATCH','DELETE'):
            from server.operations import audit_request
            audit_request(store,request,response.status_code,now_ts())
        if request.url.path == '/admin' or request.url.path.startswith(('/admin/', '/v1/admin/')):
            response.headers['Cache-Control'] = 'no-store'
            response.headers['X-Content-Type-Options'] = 'nosniff'
            response.headers['X-Frame-Options'] = 'DENY'
            response.headers['Referrer-Policy'] = 'no-referrer'
            response.headers['Content-Security-Policy'] = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        return response

    @app.get('/admin', include_in_schema=False)
    def admin_panel():
        return FileResponse(admin_dir / 'index.html', media_type='text/html')

    @app.get('/admin/style.css', include_in_schema=False)
    def admin_style():
        return FileResponse(admin_dir / 'style.css', media_type='text/css')

    @app.get('/admin/app.js', include_in_schema=False)
    def admin_script():
        return FileResponse(admin_dir / 'app.js', media_type='application/javascript')

    @app.get('/healthz')
    def healthz():
        return {'status':'ok'}

    @app.get('/v1/public/config')
    def public_config():
        return {'registration_open':registration_open,'version':app.version}

    @app.post('/v1/accounts', status_code=201)
    def create_account(payload: ProfileIn, request: Request):
        if not registration_open:
            raise HTTPException(403,'Регистрация временно закрыта организатором')
        limit('registration',request.client.host if request.client else 'unknown',30,3600)
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
            if db.execute('SELECT 1 FROM club_members WHERE account_id=?',(user['id'],)).fetchone():
                raise HTTPException(409,'Перед изменением анкеты выйди из команды')
            if _active_match_id(db,user['id']):
                raise HTTPException(409, 'Заверши текущий матч перед изменением анкеты')
            high_rank=max((payload.current_rank,payload.previous_rank), key=lambda v:RANK_VALUES[v])
            played = db.execute('SELECT 1 FROM rating_changes WHERE account_id=? LIMIT 1',(user['id'],)).fetchone()
            if played and (payload.riot_id.casefold()!=user['riot_id'].casefold() or payload.region!=user['region']):
                raise HTTPException(409,'После подтверждённых матчей смена Riot ID или региона требует отдельной проверки')
            identity_changed = payload.riot_id.casefold()!=user['riot_id'].casefold() or payload.region!=user['region']
            if identity_changed:
                social = db.execute('SELECT 1 FROM friendships WHERE a=? OR b=? LIMIT 1',(user['id'],user['id'])).fetchone()
                history = db.execute('SELECT 1 FROM match_players WHERE account_id=? LIMIT 1',(user['id'],)).fetchone()
                if social or history:
                    raise HTTPException(409,'Смена Riot ID или региона после взаимодействия с игроками требует отдельной проверки')
            rating = user['rating'] if played else RANK_SEEDS[high_rank]
            db.execute('DELETE FROM queue WHERE account_id=?',(user['id'],))
            db.execute('DELETE FROM evidence WHERE account_id=?',(user['id'],))
            db.execute('DELETE FROM session_signups WHERE account_id=?',(user['id'],))
            try:
                db.execute('''UPDATE accounts SET riot_id=?,region=?,current_rank=?,previous_rank=?,
                    primary_role=?,secondary_role=?,rating=?,status='PENDING_REVIEW' WHERE id=?''',
                    (payload.riot_id,payload.region,payload.current_rank,payload.previous_rank,
                     payload.primary,payload.secondary,rating,user['id']))
            except sqlite3.IntegrityError:
                raise HTTPException(409, 'Такой Riot ID уже зарегистрирован')
            return account_dict(db,db.execute('SELECT * FROM accounts WHERE id=?',(user['id'],)).fetchone())

    @app.post('/v1/me/evidence')
    async def submit_evidence(image: UploadFile=File(...),token=Depends(token_header)):
        with store.transaction() as db:
            user=get_user(db,token)
            ident=user['id']
        limit('evidence',ident,20,3600)
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

    @app.post('/v1/me/evidence/{slot}/ocr')
    def own_ocr(slot:int,token=Depends(token_header)):
        with store.transaction() as db:
            ident=get_user(db,token)['id']
        limit('ocr',ident,12,3600)
        return read_ocr(ident,slot)

    def read_ocr(ident,slot):
        with store.transaction() as db:
            row=db.execute('SELECT content FROM evidence WHERE account_id=? AND slot=?',(ident,slot)).fetchone()
        if row is None:
            raise HTTPException(404,'Документ не найден')
        try:
            return recognize_stats(row['content'])
        except OcrUnavailable:
            raise HTTPException(503,'OCR временно недоступен')

    @app.post('/v1/admin/accounts/{account_id}/evidence/{slot}/ocr',dependencies=[Depends(admin_auth)])
    def admin_ocr(account_id:str,slot:int):
        limit('admin_ocr','moderator',60,3600)
        return read_ocr(account_id,slot)

    @app.get('/v1/sessions')
    def sessions(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            clean_session_queues(db)
            rows=db.execute('SELECT * FROM sessions WHERE region=? AND ends_at>? AND cancelled=0 ORDER BY starts_at LIMIT 50',(user['region'],now_ts())).fetchall()
            return {'sessions':[session_info(db,r,user['id']) for r in rows]}

    @app.post('/v1/sessions/{sid}/signup')
    def session_signup(sid:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            event=db.execute('SELECT * FROM sessions WHERE id=? AND region=?',(sid,user['region'])).fetchone()
            if not event: raise HTTPException(404,'Сессия не найдена')
            if user['status']!='APPROVED': raise HTTPException(403,'Нужен допуск')
            if session_status(event) not in ('UPCOMING','OPEN'): raise HTTPException(409,'Запись закрыта')
            current=session_info(db,event,user['id'])
            if current['enrolled']: return current
            overlap=db.execute("""SELECT 1 FROM session_signups ss JOIN sessions s ON s.id=ss.session_id
                WHERE ss.account_id=? AND s.cancelled=0 AND s.starts_at<? AND s.ends_at>?""",(user['id'],event['ends_at'],event['starts_at'])).fetchone()
            if overlap: raise HTTPException(409,'Уже есть запись на пересекающееся время')
            if current['registered']>=event['capacity']: raise HTTPException(409,'Все места заняты')
            db.execute('INSERT INTO session_signups VALUES(?,?,?)',(sid,user['id'],now_ts()))
            return session_info(db,event,user['id'])

    @app.post('/v1/sessions/{sid}/withdraw')
    def session_withdraw(sid:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            event=db.execute('SELECT * FROM sessions WHERE id=? AND region=?',(sid,user['region'])).fetchone()
            if not event: raise HTTPException(404,'Сессия не найдена')
            mid=_active_match_id(db,user['id'])
            if mid and db.execute('SELECT 1 FROM match_sessions WHERE match_id=? AND session_id=?',(mid,sid)).fetchone():
                raise HTTPException(409,'Сначала заверши назначенный матч')
            if db.execute('SELECT 1 FROM queue_sessions WHERE account_id=? AND session_id=?',(user['id'],sid)).fetchone():
                db.execute('DELETE FROM queue WHERE account_id=?',(user['id'],))
            db.execute('DELETE FROM session_signups WHERE session_id=? AND account_id=?',(sid,user['id']))
            return session_info(db,event,user['id'])

    @app.get('/v1/admin/sessions',dependencies=[Depends(admin_auth)])
    def admin_sessions():
        with store.transaction(write=True) as db:
            clean_session_queues(db)
            rows=db.execute('SELECT * FROM sessions ORDER BY starts_at DESC LIMIT 100').fetchall()
            return {'sessions':[session_info(db,r) for r in rows]}

    @app.post('/v1/admin/sessions',status_code=201,dependencies=[Depends(admin_auth)])
    def admin_create_session(payload:SessionIn):
        with store.transaction(write=True) as db:
            if db.execute('SELECT 1 FROM sessions WHERE region=? AND cancelled=0 AND starts_at<? AND ends_at>?',(payload.region,payload.ends_at,payload.starts_at)).fetchone():
                raise HTTPException(409,'В регионе уже есть сессия на это время')
            sid=uuid.uuid4().hex
            db.execute('INSERT INTO sessions VALUES(?,?,?,?,?,?,0)',(sid,payload.title,payload.region,payload.starts_at,payload.ends_at,payload.capacity))
            return session_info(db,db.execute('SELECT * FROM sessions WHERE id=?',(sid,)).fetchone())

    @app.post('/v1/admin/sessions/{sid}/cancel',dependencies=[Depends(admin_auth)])
    def admin_cancel_session(sid:str):
        with store.transaction(write=True) as db:
            event=db.execute('SELECT * FROM sessions WHERE id=?',(sid,)).fetchone()
            if not event: raise HTTPException(404,'Сессия не найдена')
            if session_status(event)=='ENDED': raise HTTPException(409,'Сессия уже завершилась')
            db.execute('UPDATE sessions SET cancelled=1 WHERE id=?',(sid,))
            clean_session_queues(db)
            return {'status':'CANCELLED'}

    @app.get('/v1/queue/state')
    def queue_state(token=Depends(token_header)):
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            _expire_ready_checks(db)
            expire_unresolved_matches(db)
            clean_session_queues(db)
            match_id=_active_match_id(db,user['id'])
            queued=db.execute('SELECT 1 FROM queue WHERE account_id=?',(user['id'],)).fetchone() is not None
            if queued: touch_queue(db,user['id'])
            scope=db.execute('SELECT session_id FROM queue_sessions WHERE account_id=?',(user['id'],)).fetchone()
            sid=scope['session_id'] if scope else None
            count=db.execute('SELECT count(*) FROM queue q LEFT JOIN queue_sessions qs ON q.account_id=qs.account_id WHERE q.region=? AND qs.session_id IS ?',(user['region'],sid)).fetchone()[0]
            return {'account_status':user['status'],'queued':queued,'waiting_in_region':count,
                    'session_id':sid, 'discipline':discipline(db,user['id']), 'match':_match_info(db,match_id,viewer_id=user['id']) if match_id else None}

    @app.post('/v1/queue/join')
    def join_queue(session_id:str|None=None,token=Depends(token_header)):
        with store.transaction() as db:
            ident=get_user(db,token)['id']
        limit('queue_join',ident,30,60)
        # Commit expiration before enforcing cooldown; an HTTP error must not roll it back.
        with store.transaction(write=True) as db:
            _expire_ready_checks(db)
            expire_unresolved_matches(db)
            clean_session_queues(db)
        with store.transaction(write=True) as db:
            user=get_user(db,token)
            blocked=discipline(db,user['id'])['blocked_until']
            if blocked:
                raise HTTPException(429,'Временное ограничение поиска после отмены матча',headers={'Retry-After':str(max(1,blocked-now_ts()))})
            if user['status'] != 'APPROVED':
                raise HTTPException(403, 'Требуется одобрение заявки модератором')
            if _active_match_id(db,user['id']):
                raise HTTPException(409, 'Игрок уже назначен на матч')
            existing=db.execute('SELECT qs.session_id FROM queue q LEFT JOIN queue_sessions qs ON q.account_id=qs.account_id WHERE q.account_id=?',(user['id'],)).fetchone()
            if existing and existing['session_id']!=session_id:
                raise HTTPException(409,'Сначала покинь текущую очередь')
            if session_id is not None:
                event=db.execute('SELECT * FROM sessions WHERE id=? AND region=?',(session_id,user['region'])).fetchone()
                if not event: raise HTTPException(404,'Сессия не найдена')
                if session_status(event)!='OPEN': raise HTTPException(409,'Игровое окно не открыто')
                if not db.execute('SELECT 1 FROM session_signups WHERE session_id=? AND account_id=?',(session_id,user['id'])).fetchone():
                    raise HTTPException(403,'Сначала запишись на сессию')
            db.execute('INSERT OR IGNORE INTO queue(account_id,region,joined_at) VALUES(?,?,?)',(user['id'],user['region'],now_ts()))
            if session_id: db.execute('INSERT OR IGNORE INTO queue_sessions VALUES(?,?)',(user['id'],session_id))
            touch_queue(db,user['id'])
            _try_match(db,user['region'],session_id)
            match_id=_active_match_id(db,user['id'])
            return {'queued':match_id is None,'match':_match_info(db,match_id,viewer_id=user['id']) if match_id else None}

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
            match_member(db,token,match_id)
            _expire_ready_checks(db)
        with store.transaction(write=True) as db:
            user,mp=match_member(db,token,match_id)
            match=db.execute('SELECT status,deadline FROM matches WHERE id=?',(match_id,)).fetchone()
            if match['status']!='READY_CHECK' or match['deadline']<=now_ts():
                raise HTTPException(409,'Проверка готовности завершена')
            db.execute('UPDATE match_players SET ready=1 WHERE match_id=? AND account_id=?',(match_id,user['id']))
            count=db.execute('SELECT sum(ready) FROM match_players WHERE match_id=?',(match_id,)).fetchone()[0]
            if count==10:
                db.execute("UPDATE matches SET status='ACTIVE' WHERE id=?",(match_id,))
                set_phase_deadline(db,match_id,ACTIVE_TIMEOUT_SECONDS)
            return _match_info(db,match_id,viewer_id=user['id'])

    @app.post('/v1/matches/{match_id}/decline')
    def decline(match_id:str,token=Depends(token_header)):
        with store.transaction(write=True) as db:
            match_member(db,token,match_id)
            _expire_ready_checks(db)
        with store.transaction(write=True) as db:
            user,_=match_member(db,token,match_id)
            match=db.execute('SELECT * FROM matches WHERE id=?',(match_id,)).fetchone()
            if match['status']!='READY_CHECK':
                raise HTTPException(409,'Отказ доступен только при проверке готовности')
            db.execute("UPDATE matches SET status='CANCELLED' WHERE id=?",(match_id,))
            add_incident(db,user['id'],match_id,'DECLINED')
            others=db.execute('''SELECT a.id,a.region FROM match_players mp
                JOIN accounts a ON a.id=mp.account_id
                WHERE mp.match_id=? AND a.id!=? AND a.status='APPROVED' ''',(match_id,user['id'])).fetchall()
            for other in others:
                requeue_match_player(db,match_id,other)
            clean_session_queues(db)
            scope=db.execute('SELECT session_id FROM match_sessions WHERE match_id=?',(match_id,)).fetchone()
            _try_match(db,match['region'],scope['session_id'] if scope else None)
            return {'status':'CANCELLED'}

    @app.post('/v1/matches/{match_id}/result')
    def report_result(match_id: str, payload: ResultIn, token=Depends(token_header)):
        with store.transaction(write=True) as db:
            match_member(db,token,match_id)
            expire_unresolved_matches(db)
        with store.transaction(write=True) as db:
            user, member = match_member(db,token,match_id)
            if member['role']!='BARON':
                raise HTTPException(403,'Результат подтверждает только капитан')
            match=db.execute('SELECT status FROM matches WHERE id=?',(match_id,)).fetchone()
            prior=db.execute('SELECT winner FROM match_reports WHERE match_id=? AND side=?',(match_id,member['side'])).fetchone()
            if prior:
                if prior['winner']!=payload.winner:
                    raise HTTPException(409,'Подтверждение уже отправлено')
                return _match_info(db,match_id,viewer_id=user['id'])
            if match['status']!='ACTIVE':
                raise HTTPException(409,'Матч не принимает результаты')
            db.execute('INSERT INTO match_reports VALUES(?,?,?,?)',(match_id,member['side'],payload.winner,now_ts()))
            reports=db.execute('SELECT winner FROM match_reports WHERE match_id=?',(match_id,)).fetchall()
            if len(reports)==2:
                if reports[0]['winner']==reports[1]['winner']:
                    _finish_result(db,match_id,payload.winner,'CAPTAINS')
                else:
                    db.execute("UPDATE matches SET status='DISPUTED' WHERE id=?",(match_id,))
                    set_phase_deadline(db,match_id,DISPUTE_TIMEOUT_SECONDS)
            return _match_info(db,match_id,viewer_id=user['id'])

    @app.get('/v1/me/results')
    def my_results(token=Depends(token_header)):
        with store.transaction() as db:
            user=get_user(db,token)
            rows=db.execute('''SELECT r.match_id,r.winner,r.source,r.resolved_at,mp.side,
                 rc.before_rating,rc.after_rating,mp.role FROM match_results r
                 JOIN match_players mp ON mp.match_id=r.match_id
                 LEFT JOIN rating_changes rc ON rc.match_id=r.match_id AND rc.account_id=mp.account_id
                 WHERE mp.account_id=? ORDER BY r.resolved_at DESC,r.match_id DESC LIMIT 50''',(user['id'],)).fetchall()
            return {'results':[dict(row) for row in rows]}

    @app.post('/v1/admin/matches/{match_id}/resolve', dependencies=[Depends(admin_auth)])
    def resolve_result(match_id:str,payload:ResolutionIn):
        with store.transaction(write=True) as db: expire_unresolved_matches(db)
        with store.transaction(write=True) as db:
            row=db.execute('SELECT status FROM matches WHERE id=?',(match_id,)).fetchone()
            if not row or row['status']!='DISPUTED':
                raise HTTPException(409,'Матч не находится на проверке')
            _finish_result(db,match_id,payload.winner,'MODERATOR')
            return _match_info(db,match_id,moderator=True)

    @app.get('/v1/admin/match-quality',dependencies=[Depends(admin_auth)])
    def admin_quality():
        with store.transaction() as db:
            rows=db.execute("""SELECT m.id,m.status,m.created_at,m.region,r.winner
              FROM match_quality q JOIN matches m ON m.id=q.match_id
              LEFT JOIN match_results r ON r.match_id=m.id
              ORDER BY m.created_at DESC,m.rowid DESC LIMIT 50""").fetchall()
            return {'matches':[dict(row,quality=load_quality(db,row['id'],full=True)) for row in rows]}

    @app.get('/v1/admin/accounts', dependencies=[Depends(admin_auth)])
    def admin_accounts():
        with store.transaction() as db:
            rows=db.execute('SELECT * FROM accounts ORDER BY created_at DESC').fetchall()
            return [dict(account_dict(db,row), review_revision=review_revision(db,row), duplicate_slots=duplicate_slots(db,row['id'])) for row in rows]

    @app.get('/v1/admin/matches', dependencies=[Depends(admin_auth)])
    def admin_matches():
        with store.transaction(write=True) as db:
            _expire_ready_checks(db)
            expire_unresolved_matches(db)
            rows = db.execute("SELECT id FROM matches WHERE status IN ('READY_CHECK','ACTIVE','DISPUTED') ORDER BY created_at DESC").fetchall()
            return [_match_info(db, row['id'],moderator=True) for row in rows]

    @app.get('/v1/admin/accounts/{account_id}/evidence/{slot}', dependencies=[Depends(admin_auth)])
    def admin_evidence(account_id:str,slot:int):
        with store.transaction() as db:
            image=db.execute('SELECT content FROM evidence WHERE account_id=? AND slot=?',(account_id,slot)).fetchone()
            if not image:
                raise HTTPException(404,'Скриншот не найден')
            return Response(content=image['content'],media_type='image/jpeg',headers={'Cache-Control':'no-store'})

    @app.post('/v1/admin/accounts/{account_id}/approve', dependencies=[Depends(admin_auth)])
    def admin_approve(account_id:str, x_review_revision: str | None = Header(default=None)):
        with store.transaction(write=True) as db:
            user=db.execute('SELECT * FROM accounts WHERE id=?',(account_id,)).fetchone()
            if not user:
                raise HTTPException(404,'Участник не найден')
            if x_review_revision is not None and not hmac.compare_digest(x_review_revision, review_revision(db,user)):
                raise HTTPException(409,'Анкета изменилась. Обнови заявку и проверь документы заново.')
            evidence=db.execute('SELECT count(*) FROM evidence WHERE account_id=?',(account_id,)).fetchone()[0]
            if evidence<2:
                raise HTTPException(409,'Нужно минимум два подтверждающих скриншота')
            db.execute("UPDATE accounts SET status='APPROVED' WHERE id=?",(account_id,))
            return account_dict(db,db.execute('SELECT * FROM accounts WHERE id=?',(account_id,)).fetchone())

    @app.post('/v1/admin/accounts/{account_id}/reject', dependencies=[Depends(admin_auth)])
    def admin_reject(account_id:str, x_review_revision: str | None = Header(default=None)):
        with store.transaction(write=True) as db:
            user = db.execute('SELECT * FROM accounts WHERE id=?', (account_id,)).fetchone()
            if not user:
                raise HTTPException(404,'Участник не найден')
            if x_review_revision is not None and not hmac.compare_digest(x_review_revision, review_revision(db,user)):
                raise HTTPException(409,'Анкета изменилась. Обнови заявку.')
            if _active_match_id(db, account_id):
                raise HTTPException(409,'Нельзя отклонить участника во время назначенного матча')
            changed=db.execute("UPDATE accounts SET status='REJECTED' WHERE id=?",(account_id,)).rowcount
            if not changed:
                raise HTTPException(404,'Участник не найден')
            db.execute('DELETE FROM queue WHERE account_id=?',(account_id,))
            db.execute('DELETE FROM session_signups WHERE account_id=?',(account_id,))
            return {'status':'REJECTED'}

    @app.post('/v1/admin/matches/{match_id}/close', dependencies=[Depends(admin_auth)])
    def admin_close_match(match_id:str):
        with store.transaction(write=True) as db:
            row=db.execute('SELECT status FROM matches WHERE id=?',(match_id,)).fetchone()
            if not row or row['status']!='ACTIVE':
                raise HTTPException(409,'Активный матч не найден')
            _finish_result(db,match_id,'VOID','MODERATOR')
            return {'status':'CLOSED'}

    from server.community import install_community
    install_community(app, store, token_header, admin_auth, limit, get_user, now_ts, session_status)
    from server.match_experience import install_match_experience
    install_match_experience(app,store,token_header,admin_auth,limit,match_member,get_user,now_ts,_read_safe_image,MAX_IMAGE_BYTES)
    from server.competitive import install_competitive
    install_competitive(app,store,token_header,admin_auth,limit,get_user,now_ts)
    from server.operations import install_operations
    install_operations(app,store,token_header,admin_auth,limit,get_user,now_ts,account_dict)
    return app


app = create_app(os.environ.get('SCRIM_DB_PATH','/tmp/scrim-room-pilot.db'),
                 admin_key=os.environ.get('SCRIM_ADMIN_KEY'),
                 registration_open=os.environ.get('SCRIM_REGISTRATION_OPEN','1')=='1')
