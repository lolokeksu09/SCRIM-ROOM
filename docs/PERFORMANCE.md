# RC.7 performance verification

Run from repository root in a disposable test environment:

```sh
python -m pytest -q -rP server/tests/test_batch_history.py server/tests/test_pagination_performance.py server/tests/test_load_smoke.py
```

The batch parity fixture uses 240 synthetic players with 36 rounds, selects 217 candidates and compares the exact enriched history/peer output against the previous per-player reference. Four SQL queries are the deterministic regression budget, independent of CPU speed. The load smoke uses 240 synthetic accounts, 864 closed historical matches, 100 live waiting players lacking all required roles, 12 concurrent callers, 240 queue reads and 12 paged admin reads. It asserts queue safety, counts and successful responses, prints actual latency/elapsed/RSS in the CI log. The queue scenario deliberately does not create a real match. Existing stability and match-quality tests cover valid balanced assignments separately.

Limits: in-process ASGI timing includes application handling but excludes production TLS/network, disk and proxy characteristics; p95 is a bounded recent sample, not a guaranteed SLA. Peak RSS is process-wide including tests/dependencies. Do not extrapolate capacity from this fixture. Target host soak testing, actual device profiling, and registration opening remain separate approval gates.
