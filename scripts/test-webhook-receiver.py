import http.server

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)
        status = 500 if self.path.endswith('/fail') else 200
        print(self.path, status, len(body), flush=True)
        self.send_response(status)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, *args):
        pass

http.server.ThreadingHTTPServer(('127.0.0.1', 9200), Handler).serve_forever()
