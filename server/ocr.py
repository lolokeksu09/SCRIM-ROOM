"""Bounded Tesseract recognition. Suggestions only, never evidence of ownership."""
import io
import re
import shutil
import subprocess
import threading
from PIL import Image, ImageOps

class OcrUnavailable(Exception):
    pass

_gate=threading.BoundedSemaphore(1)

def parse_stats(text):
    def extract(labels, pattern, convert, valid):
        hits=set()
        for m in re.finditer(r'(?:'+labels+r')\s*[:=]?\s*('+pattern+r')',text,re.I):
            try:
                value=convert(m.group(1))
                if valid(value): hits.add(value)
            except ValueError:
                pass
        return next(iter(hits)) if len(hits)==1 else None
    num=lambda x:float(x.replace(',','.'))
    matches=extract(r'\bmatches\b|\bgames\b|\bматч(?:ей|и)?\b|\bигр(?:ы|а)?\b',r'\d{1,6}(?![\d.,])',int,lambda x:0<=x<=100000)
    win=extract(r'\bwin\s*rate\b|\bвинрейт\b|\bпроцент\s+побед\b',r'\d{1,3}(?:[.,]\d{1,2})?\s*%',lambda x:num(x.rstrip('%').strip()),lambda x:0<=x<=100)
    kda=extract(r'\bKDA\b|\bКДА\b',r'\d{1,3}(?:[.,]\d{1,2})?(?![\d.,/])',num,lambda x:0<=x<=100)
    ranks=[key for key,p in [('GRANDMASTER',r'\bgrandmaster\b|\bграндмастер\b'),('CHALLENGER',r'\bchallenger\b|\bпретендент\b'),('SOVEREIGN',r'\bsovereign\b|\bсуверен\b'),('MASTER',r'\bmaster\b|\bмастер\b')] if re.search(p,text,re.I)]
    return {'matches':matches,'win_rate':win,'kda':kda,'rank_candidates':ranks}

def recognize_stats(content):
    exe=shutil.which('tesseract')
    if not exe or not _gate.acquire(blocking=False):
        raise OcrUnavailable()
    try:
        with Image.open(io.BytesIO(content)) as im:
            im.thumbnail((2400,2400))
            gray=ImageOps.autocontrast(ImageOps.grayscale(im))
            out=io.BytesIO()
            gray.save(out,format='PNG')
        try:
            result=subprocess.run([exe,'stdin','stdout','-l','eng+rus','--psm','11'],input=out.getvalue(),capture_output=True,timeout=12,check=True)
        except (OSError,subprocess.SubprocessError) as exc:
            raise OcrUnavailable() from exc
        return {'status':'UNVERIFIED','suggestions':parse_stats(result.stdout.decode('utf-8','replace')[:12000])}
    finally:
        _gate.release()
