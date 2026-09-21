#!/usr/bin/env python3
"""Reproduces the marking procedure from the assignment, exactly.

    s = socket.create_connection(("localhost", 8080))

One socket. Every request. The connection is still open at the end.

    usage: python3 tests/persistent_socket_check.py [port]
"""
import socket
import sys

EXPECTED = [
    ("GET",  "/add?a=2&b=3",  200, "5"),
    ("GET",  "/sub?a=10&b=4", 200, "6"),
    ("GET",  "/mul?a=6&b=7",  200, "42"),
    ("GET",  "/div?a=9&b=3",  200, "3"),
    ("GET",  "/div?a=1&b=0",  400, None),
    ("GET",  "/add?a=x&b=3",  400, None),
    ("GET",  "/pow?a=2&b=8",  404, None),
    ("POST", "/add",          405, None),
]


class Reader:
    """Reads responses off one socket, consuming exactly Content-Length bytes each time."""

    def __init__(self, sock):
        self.f = sock.makefile("rb")

    def read_response(self):
        status_line = self.f.readline()
        if not status_line:
            raise AssertionError("server closed the connection instead of answering")
        status = int(status_line.split()[1])
        headers = {}
        while True:
            line = self.f.readline()
            if line in (b"\r\n", b"\n", b""):
                break
            name, _, value = line.decode("iso-8859-1").partition(":")
            headers[name.strip().lower()] = value.strip()
        if "content-length" not in headers:
            raise AssertionError(
                "response has no Content-Length; on a persistent connection there is then no "
                "way to know where it ends"
            )
        body = self.f.read(int(headers["content-length"]))
        return status, headers, body.decode("utf-8", "replace")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    failures = []

    s = socket.create_connection(("localhost", port))
    reader = Reader(s)

    for method, target, want_status, want_body in EXPECTED:
        s.sendall(
            f"{method} {target} HTTP/1.1\r\nHost: localhost:{port}\r\n"
            f"Content-Length: 0\r\n\r\n".encode()
        )
        status, _, body = reader.read_response()
        ok = status == want_status and (want_body is None or body == want_body)
        shown = repr(body) if want_body is not None else ""
        print(f"  {method:4} {target:16} -> {status:3} {shown}   {'ok' if ok else 'FAIL'}")
        if not ok:
            failures.append(f"{method} {target}: got {status} {body!r}, "
                            f"wanted {want_status} {want_body!r}")

    # The Host header is mandatory in HTTP/1.1.  The request is still framed correctly, so the
    # connection survives the 400 and we keep using it.
    s.sendall(f"GET /add?a=2&b=3 HTTP/1.1\r\nContent-Length: 0\r\n\r\n".encode())
    status, _, _ = reader.read_response()
    ok = status == 400
    print(f"  GET  /add (no Host)  -> {status:3}      {'ok' if ok else 'FAIL'}")
    if not ok:
        failures.append(f"GET /add with no Host: got {status}, wanted 400")

    # The actual grading question.
    s.sendall(f"GET /add?a=20&b=22 HTTP/1.1\r\nHost: localhost:{port}\r\n\r\n".encode())
    status, _, body = reader.read_response()
    still_open = status == 200 and body == "42"

    print()
    print(f"socket still open: {still_open}")
    print(f"1 TCP handshake, {len(EXPECTED) + 2} responses")
    s.close()

    if failures or not still_open:
        print()
        for f in failures:
            print("FAIL:", f)
        if not still_open:
            print("FAIL: the socket did not survive all nine requests")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
