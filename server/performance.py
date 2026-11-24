"""Bounded process-local aggregate latency samples. No request IDs, bodies or credentials."""
import math
import threading
import sys
import resource
from collections import deque

class PerformanceWindow:
    def __init__(self,capacity=512):
        self.capacity=capacity
        self.lock=threading.Lock()
        self.groups={name:deque(maxlen=capacity) for name in ('queue','admin','match','other')}
    def record(self,path,status,seconds):
        if not path.startswith('/v1/'):return
        group='queue' if path.startswith('/v1/queue/') else 'admin' if path.startswith('/v1/admin/') else 'match' if path.startswith('/v1/matches/') else 'other'
        with self.lock:self.groups[group].append((max(0,seconds)*1000,int(status)>=500))
    def snapshot(self):
        with self.lock: data={k:list(v) for k,v in self.groups.items()}
        groups={}
        for name,samples in data.items():
            if not samples:continue
            values=sorted(t for t,_ in samples)
            groups[name]={'samples':len(values),'p50_ms':round(values[math.ceil(len(values)*.5)-1],2),'p95_ms':round(values[math.ceil(len(values)*.95)-1],2),'max_ms':round(values[-1],2),'errors':sum(e for _,e in samples)}
        peak=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return {'capacity_per_group':self.capacity,'groups':groups,'process_peak_rss_mib':round(peak/(1024*1024 if sys.platform=='darwin' else 1024),1)}
