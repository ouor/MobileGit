"""QA test origin: smart-HTTP git server on the PC (git http-backend as CGI).
Serves bare repos under ROOT. Paths under /private/ need Basic auth qa:qa-token-1234.
Phone reaches it through `adb reverse tcp:9418 tcp:9418` -> http://127.0.0.1:9418/<repo>.git
Usage: python origin_server.py <root>
"""
import base64, os, subprocess, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
ROOT = os.path.abspath(sys.argv[1])
BACKEND = r"C:\Program Files\Git\mingw64\libexec\git-core\git-http-backend.exe"
TOKEN = "Basic " + base64.b64encode(b"qa:qa-token-1234").decode()

class H(BaseHTTPRequestHandler):
    def handle_any(self):
        path, _, query = self.path.partition("?")
        if path.startswith("/private/") and self.headers.get("Authorization") != TOKEN:
            self.send_response(401); self.send_header("WWW-Authenticate", 'Basic realm="qa"')
            self.send_header("Content-Length", "0"); self.end_headers(); return
        env = dict(os.environ, GIT_PROJECT_ROOT=ROOT, GIT_HTTP_EXPORT_ALL="1", PATH_INFO=path,
                   QUERY_STRING=query, REQUEST_METHOD=self.command, REMOTE_USER="qa",
                   CONTENT_TYPE=self.headers.get("Content-Type", ""), REMOTE_ADDR=self.client_address[0])
        if self.headers.get("Git-Protocol"): env["GIT_PROTOCOL"] = self.headers["Git-Protocol"]
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        if self.headers.get("Content-Encoding") == "gzip": env["HTTP_CONTENT_ENCODING"] = "gzip"
        out = subprocess.run([BACKEND], input=body, env=env, capture_output=True).stdout
        head, _, rest = out.partition(b"\r\n\r\n")
        status = 200
        hdrs = []
        for line in head.decode("latin1").split("\r\n"):
            if not line: continue
            k, _, v = line.partition(":")
            if k.lower() == "status": status = int(v.strip().split()[0])
            else: hdrs.append((k, v.strip()))
        self.send_response(status)
        for k, v in hdrs: self.send_header(k, v)
        self.send_header("Content-Length", str(len(rest))); self.end_headers(); self.wfile.write(rest)
    do_GET = do_POST = handle_any

ThreadingHTTPServer(("127.0.0.1", 9418), H).serve_forever()
