"""Real Chromium + loopback FastAPI smoke test. Synthetic data only."""
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import urlopen

from fastapi.testclient import TestClient
from playwright.sync_api import sync_playwright, expect
from server.app import create_app
from server.tests.test_api import ADMIN, register, auth, photo

out = Path("admin-browser-evidence")
out.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    db = str(Path(tmp) / "pilot.db")
    with TestClient(create_app(db, admin_key=ADMIN)) as c:
        account = register(c, 901)
        for i in (1, 2):
            response = c.post("/v1/me/evidence", headers=auth(account),
                              files={"image": (f"{i}.png", photo(i), "image/png")})
            assert response.status_code == 200
    env = {**os.environ, "SCRIM_DB_PATH": db, "SCRIM_ADMIN_KEY": ADMIN}
    base = "http://127.0.0.1:8765"
    with (out / "server.log").open("w") as log:
        server = subprocess.Popen([sys.executable, "-m", "uvicorn", "server.app:app",
                                   "--host", "127.0.0.1", "--port", "8765"], env=env, stdout=log, stderr=log)
        try:
            for _ in range(50):
                try:
                    with urlopen(base + "/healthz", timeout=1) as r:
                        if r.status == 200:
                            break
                except OSError:
                    time.sleep(.2)
            else:
                raise RuntimeError("Test API did not start")
            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = browser.new_page(viewport={"width": 1280, "height": 900})
                errors = []
                page.on("pageerror", lambda e: errors.append(str(e)))
                page.on("dialog", lambda d: d.accept())
                page.goto(base + "/admin")
                page.locator("#key").fill("wrong-key-that-is-long-enough-12345")
                page.get_by_role("button", name="Войти", exact=True).click()
                expect(page.locator("#notice")).to_contain_text("Доступ запрещён")
                page.locator("#key").fill(ADMIN)
                page.get_by_role("button", name="Войти", exact=True).click()
                expect(page.locator("#workspace")).to_be_visible()
                expect(page.locator("#login")).to_be_hidden()
                page.locator("#search").fill("not-found")
                expect(page.locator("#list")).to_contain_text("Нет заявок")
                page.locator("#search").clear()
                page.locator("#list .account").first.click()
                expect(page.locator("#detail h2").first).to_have_text("Pilot901#EUW")
                expect(page.locator(".evidence img")).to_have_count(2)
                page.get_by_role("button", name="Открыть документ 1").click()
                expect(page.locator("#preview")).to_be_visible()
                page.get_by_role("button", name="Закрыть", exact=True).click()
                expect(page.locator("#preview")).to_be_hidden()
                page.screenshot(path=str(out / "01-review-desktop.png"), full_page=True)
                page.set_viewport_size({"width": 390, "height": 844})
                page.screenshot(path=str(out / "02-review-mobile.png"), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                page.get_by_role("button", name="Допустить", exact=True).click()
                expect(page.locator("#detail")).to_contain_text("Допущен")
                page.locator("#filter").select_option("APPROVED")
                expect(page.locator("#list .account")).to_have_count(1)
                page.get_by_role("button", name="Отклонить", exact=True).click()
                expect(page.locator("#detail")).to_contain_text("Отклонён")
                page.get_by_role("button", name="Матчи", exact=True).click()
                expect(page.locator("#matchList")).to_contain_text("Назначенных матчей нет")
                page.get_by_role("button", name="Выйти", exact=True).click()
                expect(page.locator("#workspace")).to_be_hidden()
                expect(page.locator("#login")).to_be_visible()
                assert page.evaluate("localStorage.length === 0 && sessionStorage.length === 0")
                assert not errors, errors
                page.reload()
                expect(page.locator("#login")).to_be_visible()
                browser.close()
                print("PASS: real browser login denial, authentication, filters, evidence preview, mobile layout, approval, rejection, matches and logout")
        finally:
            server.terminate()
            server.wait(timeout=10)
