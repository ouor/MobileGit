"""Take web screenshots. Usage: python web_shots.py <base> <pwfile> <out-dir> name=path[@dark][#mobile] ..."""
import sys
from playwright.sync_api import sync_playwright
base, pwfile, out = sys.argv[1:4]
pw = open(pwfile).read().strip()
with sync_playwright() as p:
    b = p.chromium.launch()
    for spec in sys.argv[4:]:
        name, path = spec.split("=", 1)
        dark = "@dark" in path; mobile = "#mobile" in path
        path = path.replace("@dark", "").replace("#mobile", "")
        ctx = b.new_context(http_credentials={"username": "git", "password": pw}, color_scheme="dark" if dark else "light",
                            viewport={"width": 390, "height": 844} if mobile else {"width": 1440, "height": 900},
                            device_scale_factor=2 if mobile else 1.5, is_mobile=mobile)
        pg = ctx.new_page(); pg.goto(base + path, wait_until="networkidle"); pg.wait_for_timeout(900)
        pg.screenshot(path=f"{out}/{name}.png"); ctx.close(); print("saved", name)
    b.close()
