"""Consistent SQLite backup CLI. Run as the server owner; never expose backups over HTTP."""
import argparse
import os
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path

def backup(source,destination):
    src=Path(source).resolve()
    dest=Path(destination).resolve()
    if not src.is_file(): raise FileNotFoundError('Source database missing')
    if src==dest or dest.exists(): raise FileExistsError('Destination already exists')
    dest.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    fd,tmp=tempfile.mkstemp(prefix='.scrim-backup-',dir=dest.parent)
    os.close(fd)
    try:
        with closing(sqlite3.connect(src.as_uri()+'?mode=ro',uri=True)) as a, closing(sqlite3.connect(tmp)) as b:
            a.backup(b)
            if b.execute('PRAGMA quick_check').fetchall()!=[('ok',)]: raise RuntimeError('Backup integrity check failed')
        # Hard-link publication refuses an existing destination atomically.
        os.link(tmp,dest)
        return dest
    finally:
        os.unlink(tmp)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination')
    parser.add_argument('--source',default=os.environ.get('SCRIM_DB_PATH','/data/scrim.sqlite3'))
    args=parser.parse_args()
    print(backup(args.source,args.destination))
if __name__=='__main__': main()
