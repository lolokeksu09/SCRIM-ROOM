"""Scheduled logical retention, preserving duplicate-proof detection."""
def prune_expired(db,stamp):
    cutoff=stamp-30*86400
    # Legacy evidence may predate the hash-history table. Preserve fingerprints
    # before deleting bytes. Never remove evidence on an unresolved match.
    old=db.execute("""SELECT e.match_id,e.side,e.sha256 FROM result_evidence e
        JOIN matches m ON m.id=e.match_id
        WHERE e.created_at<? AND m.status IN ('CLOSED','CANCELLED') LIMIT 100""",(cutoff,)).fetchall()
    db.executemany('INSERT OR IGNORE INTO result_proof_history VALUES(?,?,?)',[(r['match_id'],r['side'],r['sha256']) for r in old])
    db.executemany('DELETE FROM result_evidence WHERE match_id=? AND side=?',[(r['match_id'],r['side']) for r in old])
    db.execute('DELETE FROM admin_audit WHERE id IN (SELECT id FROM admin_audit WHERE at<? LIMIT 1000)',(stamp-90*86400,))
