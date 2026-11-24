# RC.7 performance verification

Run from repository root in a disposable test environment:

```sh
python -m pytest -q -rP server/tests/test_batch_history.py server/tests/test_pagination_performance.py server/tests/test_load_smoke.py
```

The batch parity fixture uses 240 synthetic players with 36 rounds, selects 217 candidates and compares the exact enriched history/peer output against the previous per-player reference. Four SQL queries are the deterministic regression budget, independent of CPU speed. The load smoke uses 240 synthetic accounts, 864 closed historical matches, 100 live waiting players lacking all required roles, 12 concurrent callers, 240 queue reads and 12 paged admin reads. It asserts queue safety, counts and successful responses, prints actual latency/elapsed/RSS in the CI log. The queue scenario deliberately does not create a real match. Existing stability and match-quality tests cover valid balanced assignments separately.

Limits: in-process ASGI timing includes application handling but excludes production TLS/network, disk and proxy characteristics; p95 is a bounded recent sample, not a guaranteed SLA. Peak RSS is process-wide including tests/dependencies. Do not extrapolate capacity from this fixture. Target host soak testing, actual device profiling, and registration opening remain separate approval gates.

## Measured baseline: RC.7
Exact revision `692d74774ca45dcaf60bf263b0b6e9833f5905ac`, GitHub Actions run [45336259232](https://github.com/lolokeksu09/SCRIM-ROOM/actions/runs/45336259232), API job 138248934032. 53 passed, 1 warning, 26.02 s for the suite. Load smoke: 252 requests, 12 concurrent callers, 1.47 s elapsed; queue p50 67.30 ms, p95 69.55 ms, max 70.60 ms, 0 errors; admin p50 53.23 ms, p95 67.78 ms, max 67.78 ms, 0 errors. Peak process RSS 100.6 MiB. These are synthetic ASGI measurements; not a host benchmark or production capacity claim.
