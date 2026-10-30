#!/usr/bin/env python3
"""Serves one demo directory to the emulator, slowly where that is the point.

No-store, because a demo page that is edited and re-recorded must not come back
from Gecko's cache as yesterday's copy. Throttled for `.bin`, because a download
over host loopback finishes before a progress notification has drawn itself, and
a progress bar that is never seen is the one thing the download video has to
show.

    python3 serve.py <directory> <port>
"""

import http.server
import os
import sys
import time

RATE = 1_200_000  # bytes/second for .bin, so 12 MB takes about ten seconds
CHUNK = 64 * 1024


class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        if self.path.endswith(".bin"):
            # Makes Gecko offer it as a download rather than render it.
            self.send_header("Content-Disposition", "attachment")
        super().end_headers()

    def copyfile(self, source, outputfile):
        if not self.path.endswith(".bin"):
            return super().copyfile(source, outputfile)
        while chunk := source.read(CHUNK):
            outputfile.write(chunk)
            time.sleep(len(chunk) / RATE)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    os.chdir(sys.argv[1])
    http.server.HTTPServer(("127.0.0.1", int(sys.argv[2])), Handler).serve_forever()
