"""Bounded fixed-roster reservation: no overlapping individual play around a scheduled fixture."""
import json
from fastapi import HTTPException

# Maximum individual ready check + active lifetime. A scheduled fixture's
# playing window is two hours. Late resolution is administrative, not extra play time.
LEAD=7380
WINDOW=7200

def reserved_players(db,stamp):
    rows=db.execute("""SELECT roster_a,roster_b FROM club_fixtures
      WHERE status='SCHEDULED' AND starts_at<=? AND starts_at>?""",(stamp+LEAD,stamp-WINDOW)).fetchall()
    return {p['id'] for row in rows for key in ('roster_a','roster_b') for p in json.loads(row[key])}

def enforce_reservations(db,stamp):
    db.executemany('DELETE FROM queue WHERE account_id=?',[(ident,) for ident in reserved_players(db,stamp)])

def latest_individual_end(db,rosters,stamp):
    latest=stamp
    for p in rosters:
        rows=db.execute("""SELECT m.status,m.deadline,d.expires_at FROM match_players p
          JOIN matches m ON m.id=p.match_id LEFT JOIN match_phase_deadlines d ON d.match_id=m.id
          WHERE p.account_id=? AND m.status IN ('READY_CHECK','ACTIVE')""",(p['id'],)).fetchall()
        for row in rows:
            latest=max(latest,(row['deadline']+WINDOW if row['status']=='READY_CHECK' else (row['expires_at'] or row['deadline']+WINDOW)))
    return latest

def check_fixture_conflict(db,rosters,start,stamp):
    if latest_individual_end(db,rosters,stamp)>start:
        raise HTTPException(409,'Участник уже назначен на индивидуальный матч, который может пересечься со встречей')
