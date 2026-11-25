"""A stand-in for GitHub releases for the installers' update check (run.sh): GET /latest answers 302 to
/tag/v<the text of latest.txt>, GET /download/v<ver>/<file> serves <dir>/<file>. Everything else is 404.
usage: fakerelease.py PORT DIR   (prints 'ready' to DIR/server.log)"""
import http.server, os, sys

port, root = int(sys.argv[1]), sys.argv[2]


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_GET(self):
        if self.path == '/latest':
            v = open(os.path.join(root, 'latest.txt')).read().strip()
            self.send_response(302); self.send_header('Location', 'http://127.0.0.1:%d/tag/v%s' % (port, v)); self.end_headers(); return
        if self.path.startswith('/download/v'):
            f = os.path.join(root, os.path.basename(self.path))
            if os.path.isfile(f):
                b = open(f, 'rb').read()
                self.send_response(200); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b); return
        self.send_response(404); self.end_headers()
    do_HEAD = do_GET


s = http.server.HTTPServer(('127.0.0.1', port), H)
open(os.path.join(root, 'server.log'), 'w').write('ready\n')
s.serve_forever()
