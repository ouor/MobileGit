"""QA web checks (F-18) with Playwright. Usage: python web_check.py <base> <password-file> <outdir>"""
import json, sys, re
from playwright.sync_api import sync_playwright
base, pwfile, out = sys.argv[1], sys.argv[2], sys.argv[3]
pw = open(pwfile).read().strip()
res = {}
with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(http_credentials={"username": "git", "password": pw}, viewport={"width": 1366, "height": 900})
    page = ctx.new_page()
    external, csp, dialogs = [], [], []
    page.on("request", lambda r: external.append(r.url) if not r.url.startswith(base) and not r.url.startswith("data:") else None)
    page.on("console", lambda m: csp.append(m.text) if m.type == "error" else None)
    page.on("dialog", lambda d: (dialogs.append(d.message), d.dismiss()))
    def go(path, wait="networkidle"):
        page.goto(base + path, wait_until=wait); page.wait_for_timeout(600)
        return page.inner_text("body")
    t = go("/"); res["W01_list"] = [n for n in ["chalk", "awesome", "qa-origin"] if n in t]
    t = go("/r/qa-origin"); res["W02_clone_url"] = (base + "/qa-origin.git") in t; res["W02_mirror_label"] = "미러" in t
    # branch selector: open and collect options
    btn = page.locator("button[role=combobox], button:has-text('main')").first
    try:
        btn.click(); page.wait_for_timeout(500); res["W02_ref_options"] = page.locator("[role=option]").all_inner_texts()
        page.keyboard.press("Escape")
    except Exception as e: res["W02_ref_options"] = "ERR " + str(e)[:80]
    t = go("/r/qa-edge"); res["xss_dialogs"] = dialogs[:]; h = page.inner_html("body")
    res["xss_script_tag"] = "<script>alert" in h; res["xss_onerror"] = "onerror" in h; res["xss_js_link"] = "javascript:" in h
    t = go("/r/qa-edge/blob/big.txt"); res["W03_big"] = re.findall(r"(표시할 수 없[^\n]*|1\.\d+ MB|1\d{3} KB|다운로드[^\n]*)", t)[:4]
    t = go("/r/qa-edge/blob/bin.dat"); res["W03_bin"] = re.findall(r"(바이너리[^\n]*|표시할 수 없[^\n]*|다운로드[^\n]*)", t)[:3]
    t = go("/r/qa-edge/blob/lfs.psd"); res["W03_lfs"] = re.findall(r"(LFS[^\n]*)", t)[:3]
    t = go("/r/qa-edge/tree/dir%20with%20space"); res["W02b_unicode"] = "한글 파일.txt" in t
    t = go("/r/awesome/commits"); res["W04_rows_page1"] = len(re.findall(r"\b[0-9a-f]{7}\b", t))
    t = go("/r/awesome/commits?page=2"); res["W04_page2_ok"] = len(re.findall(r"\b[0-9a-f]{7}\b", t))
    t = go("/r/nope-repo"); res["WE_404"] = t[:200].replace("\n", " | ")
    t = go("/r/qa-empty"); res["WE_empty"] = t[:300].replace("\n", " | ")
    res["external_requests"] = sorted(set(external))[:10]
    res["console_errors"] = csp[:10]
    r = ctx.request.get(base + "/api/v1/repos/qa-edge/raw?path=bin.dat")
    res["raw_headers"] = {k: v for k, v in r.headers.items() if k.lower() in ("content-disposition", "x-content-type-options", "content-type", "content-length")}
    r = ctx.request.get(base + "/"); res["csp"] = r.headers.get("content-security-policy")
    for path in ["/api/v1/repos/..%2F..%2Fstate/tree", "/api/v1/repos/qa-edge/blob?path=../../HEAD", "/..%2f..%2fstate%2fsettings.json"]:
        r = ctx.request.get(base + path); res["trav " + path] = (r.status, r.text()[:80])
    # mobile layout
    m = b.new_context(http_credentials={"username": "git", "password": pw}, viewport={"width": 390, "height": 844}, is_mobile=True)
    mp = m.new_page(); mp.goto(base + "/r/chalk", wait_until="networkidle"); mp.wait_for_timeout(500)
    res["mobile_hscroll"] = mp.evaluate("document.documentElement.scrollWidth > window.innerWidth + 1")
    mp.screenshot(path=out + "/web-mobile.png")
    b.close()
print(json.dumps(res, ensure_ascii=False, indent=1))
