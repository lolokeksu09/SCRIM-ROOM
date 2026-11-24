"""Conservative proof checks. Distinct images are not proof of a real game."""
def review_reason(db,mid):
    rows=db.execute('SELECT side,sha256 FROM result_evidence WHERE match_id=?',(mid,)).fetchall()
    if {r['side'] for r in rows}!={'BLUE','RED'}: return 'MISSING_EVIDENCE'
    if len({r['sha256'] for r in rows})!=2: return 'DUPLICATE_EVIDENCE'
    for r in rows:
        if db.execute('SELECT 1 FROM result_proof_history WHERE sha256=? AND match_id!=? LIMIT 1',(r['sha256'],mid)).fetchone():
            return 'REUSED_EVIDENCE'
    return None

def result_review_reason(db,mid):
    row=db.execute('SELECT reason FROM result_review_flags WHERE match_id=?',(mid,)).fetchone()
    return row['reason'] if row else None
