"""Serves the turntable page and saves the frames it POSTs (render/frames/NNN.png)."""
import http.server, os, sys
ROOT = os.path.dirname(os.path.abspath(__file__))
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        name = self.path.strip("/").replace("..", "")
        if name.startswith("frame/"):
            path = os.path.join(ROOT, "frames", name.split("/", 1)[1] + ".png")
            open(path, "wb").write(body)
        elif name == "log":
            print("page:", body.decode(errors="replace"), flush=True)
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8765), H).serve_forever()
