"""Local disposable container smoke; never use with production credentials."""
import json
import os
import sys
import urllib.request
import urllib.error
from pathlib import Path

BASE='http://127.0.0.1:8765'
def call(path,payload=None,headers=None):
    req=urllib.request.Request(BASE+path,data=json.dumps(payload).encode() if payload is not None else None,
        headers={'Content-Type':'application/json',**(headers or {})})
    with urllib.request.urlopen(req,timeout=10) as r: return json.load(r)
profile={'riot_id':'ContainerCI#EUW','region':'EUROPE','current_rank':'GRANDMASTER','previous_rank':'MASTER','primary':'MID','secondary':'JUNGLE'}
file=Path(os.environ['RUNNER_TEMP'])/'scrim-ci-account.json'
if sys.argv[1]=='create':
    assert call('/v1/public/config')['registration_open']
    data=call('/v1/accounts',profile)
    fd=os.open(file,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as out: json.dump(data,out)
else:
    try:
        data=json.loads(file.read_text())
        assert not call('/v1/public/config')['registration_open']
        assert call('/v1/me',headers={'Authorization':'Bearer '+data['token']})['id']==data['account']['id']
        try:
            call('/v1/accounts',dict(profile,riot_id='OtherCI#EUW'))
        except urllib.error.HTTPError as e:
            assert e.code==403
        else: raise AssertionError('Registration reopened')
        print('Persistent account and closed registration verified')
    finally: file.unlink(missing_ok=True)
