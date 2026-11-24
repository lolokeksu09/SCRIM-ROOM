"""Named independent moderator credentials, configured by the operator; no plaintext keys in DB."""
import hashlib
import hmac
import json
import os
import re

class ModeratorCredentials:
    def __init__(self,owner,hashes):
        self.owner=hashlib.sha256(owner.encode()).hexdigest() if len(owner)>=32 else None
        if not isinstance(hashes,dict) or len(hashes)>50: raise ValueError('Invalid moderator configuration')
        self.hashes={}
        for name,digest in hashes.items():
            if not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9_-]{3,40}',name) or name in ('owner','legacy'):
                raise ValueError('Invalid moderator name')
            if not isinstance(digest,str) or not re.fullmatch(r'[a-f0-9]{64}',digest) or digest in self.hashes.values() or digest==self.owner:
                raise ValueError('Invalid or duplicate moderator hash')
            self.hashes[name]=digest
    def authenticate(self,key):
        if not key or not 32<=len(key)<=512:return None
        digest=hashlib.sha256(key.encode()).hexdigest()
        actor='owner' if self.owner and hmac.compare_digest(digest,self.owner) else None
        for name,expected in self.hashes.items():
            if hmac.compare_digest(digest,expected):actor=name
        return actor
    @staticmethod
    def from_environment():
        return json.loads(os.environ.get('SCRIM_MODERATOR_HASHES') or '{}')
