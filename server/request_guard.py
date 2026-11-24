"""Bound request bodies before FastAPI/Starlette parses JSON or multipart.

Limits apply to the actual received bytes, including chunked requests.
"""
import re
from starlette.responses import JSONResponse

JSON_LIMIT=32*1024
UPLOAD_LIMIT=4*1024*1024+64*1024
UPLOAD_PATH=re.compile(r'/v1/(?:me/evidence|matches/[^/]+/evidence)/?')

class RequestBodyLimit:
    def __init__(self,app):
        self.app=app

    async def __call__(self,scope,receive,send):
        if scope['type']!='http' or not scope['path'].startswith('/v1/'):
            return await self.app(scope,receive,send)
        limit=UPLOAD_LIMIT if scope['method']=='POST' and UPLOAD_PATH.fullmatch(scope['path']) else JSON_LIMIT
        async def reject(status,detail):
            await JSONResponse({'detail':detail},status_code=status,headers={'Cache-Control':'no-store'})(scope,receive,send)
        lengths=[v for k,v in scope.get('headers',[]) if k.lower()==b'content-length']
        if lengths:
            if len(lengths)!=1 or not lengths[0].isdigit() or len(lengths[0])>12:
                return await reject(400,'Некорректный Content-Length')
            if int(lengths[0])>limit:
                return await reject(413,'Запрос слишком большой')
        body=bytearray()
        while True:
            message=await receive()
            if message['type']=='http.disconnect':
                return
            chunk=message.get('body',b'')
            if len(body)+len(chunk)>limit:
                return await reject(413,'Запрос слишком большой')
            body.extend(chunk)
            if not message.get('more_body',False):
                break
        if lengths and len(body)!=int(lengths[0]):
            return await reject(400,'Некорректная длина запроса')
        delivered=False
        async def replay():
            nonlocal delivered
            if not delivered:
                delivered=True
                return {'type':'http.request','body':bytes(body),'more_body':False}
            return await receive()
        await self.app(scope,replay,send)
