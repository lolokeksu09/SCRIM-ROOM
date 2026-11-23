FROM python:3.12-slim
WORKDIR /srv
COPY server/requirements.txt /srv/server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt \
    && useradd --system --user-group --uid 10001 scrim \
    && mkdir -p /data && chown scrim:scrim /data
COPY server /srv/server
USER scrim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SCRIM_DB_PATH=/data/scrim.sqlite3
EXPOSE 8080
CMD ["uvicorn", "server.app:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1"]
