#!/usr/bin/env python3
"""An LCB/1 conformance tester, written from docs/SPEC.md alone.

This shares no code with the Java implementation. It builds every octet it sends by hand
from the wire format in the spec, which is the point: if a server passes this, the spec is
sufficient to implement against, and the protocol is a contract rather than one program's
habits.

Point it at any LCB/1 server:

    python3 tests/conformance_check.py localhost 9000 --root ./www

Exits 0 if every check passes, 1 otherwise.
"""
import argparse
import socket
import struct
import sys

PREFACE = b"LCB1"
HEADER_LEN = 8

REQUEST, RESPONSE, DATA, PING = 0x01, 0x02, 0x03, 0x04
END_MESSAGE = ACK = 0x01
MAX_PAYLOAD = 65536

STATIC = [":method", ":path", ":status", "host",
          "content-length", "content-type", "user-agent",
          "server", "date", "connection"]


# ---- wire format, built straight from the spec -------------------------------------

def encode_frame(ftype, flags, stream_id, payload=b""):
    """SPEC §2: 24-bit length, 8-bit type, 8-bit flags, 1 reserved bit, 23-bit stream id."""
    if len(payload) > 0xFFFFFF:
        raise ValueError("payload exceeds the 24-bit length field")
    return (struct.pack(">I", len(payload))[1:]          # 24-bit length
            + bytes([ftype, flags])
            + struct.pack(">I", stream_id & 0x7FFFFF)[1:]  # R=0 + 23-bit stream id
            + payload)


def encode_headers(fields):
    """SPEC §6: name code, optional literal name, 16-bit value length, value."""
    out = b""
    for name, value in fields:
        value = value.encode("utf-8")
        if name in STATIC:
            out += bytes([STATIC.index(name) + 1])
        else:
            n = name.encode("utf-8")
            out += bytes([0, len(n)]) + n
        out += struct.pack(">H", len(value)) + value
    return out


def decode_headers(block):
    """Inverse of encode_headers. Raises on anything the spec calls malformed."""
    fields, i = [], 0
    while i < len(block):
        code = block[i]; i += 1
        if code == 0:
            ln = block[i]; i += 1
            name = block[i:i + ln].decode("utf-8"); i += ln
        else:
            if code > len(STATIC):
                raise ValueError(f"undefined static index {code}")
            name = STATIC[code - 1]
        (vlen,) = struct.unpack(">H", block[i:i + 2]); i += 2
        value = block[i:i + vlen].decode("utf-8"); i += vlen
        fields.append((name, value))
    return fields


class Conn:
    """One connection. Opened once, reused for everything, exactly as the spec requires."""

    def __init__(self, host, port, timeout=10):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.f = self.sock.makefile("rb")
        self.sock.sendall(PREFACE)
        self.authority = f"{host}:{port}"

    def send(self, *args, **kwargs):
        self.sock.sendall(encode_frame(*args, **kwargs))

    def send_raw(self, data):
        self.sock.sendall(data)

    def read_frame(self):
        head = self.f.read(HEADER_LEN)
        if len(head) < HEADER_LEN:
            return None
        length = int.from_bytes(head[0:3], "big")
        ftype, flags = head[3], head[4]
        stream = int.from_bytes(head[5:8], "big") & 0x7FFFFF
        return ftype, flags, stream, self.f.read(length)

    def read_message(self):
        """Collects one response: a RESPONSE frame plus DATA until END_MESSAGE."""
        status, headers, body, frames = None, [], b"", 0
        while True:
            frame = self.read_frame()
            if frame is None:
                raise AssertionError("server closed mid-response")
            ftype, flags, _stream, payload = frame
            if ftype == RESPONSE:
                headers = decode_headers(payload)
                status = int(dict(headers)[":status"])
                if flags & END_MESSAGE:
                    return status, headers, body, frames
            elif ftype == DATA:
                frames += 1
                body += payload
                if flags & END_MESSAGE:
                    return status, headers, body, frames
            elif ftype not in (REQUEST, PING):
                continue          # unknown type: the spec says skip it and carry on

    def request(self, path, stream_id, method="GET"):
        self.send(REQUEST, END_MESSAGE, stream_id, encode_headers([
            (":method", method), (":path", path), ("host", self.authority)]))
        return self.read_message()

    def close(self):
        self.sock.close()


# ---- the checklist -----------------------------------------------------------------

class Checker:
    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, section, description, fn):
        try:
            fn()
            self.passed += 1
            print(f"  [ok]   {section:6} {description}")
        except Exception as e:
            self.failed.append((section, description, e))
            print(f"  [FAIL] {section:6} {description}\n           {e}")


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def run(host, port, index_path):
    c = Checker()

    def conn():
        return Conn(host, port)

    # --- §1 Connection ---
    def preface_rejected():
        s = socket.create_connection((host, port), timeout=10)
        s.sendall(b"GET / HTTP/1.1\r\n\r\n")
        expect(s.recv(64) == b"", "server replied to a bad preface instead of closing")
        s.close()
    c.check("§1", "a wrong preface is closed without a reply", preface_rejected)

    def serves_a_file():
        k = conn()
        status, headers, body, _ = k.request(index_path, 1)
        expect(status == 200, f"expected 200, got {status}")
        expect(len(body) > 0, "200 response had an empty body")
        declared = dict(headers).get("content-length")
        expect(declared is not None, "no content-length on a 200")
        expect(int(declared) == len(body),
               f"content-length {declared} != {len(body)} octets of body")
        k.close()
    c.check("§7", "serves a file with a correct content-length", serves_a_file)

    def persistent():
        k = conn()
        for i, sid in enumerate([1, 3, 5, 7]):
            status, _, _, _ = k.request(index_path, sid)
            expect(status == 200, f"request {i + 1} on the same connection got {status}")
        k.close()
    c.check("§1", "one connection serves many requests", persistent)

    # --- §4 the rule that may not be skipped ---
    def skips_unknown_type():
        k = conn()
        k.send(0x7F, 0xFF, 2, b"a frame from a later version")
        k.send(0x42, 0x00, 0, b"")
        status, _, _, _ = k.request(index_path, 1)
        expect(status == 200, f"server did not recover from unknown frames (got {status})")
        k.close()
    c.check("§4", "unknown frame types are skipped, connection survives", skips_unknown_type)

    def ignores_reserved_bit():
        k = conn()
        # R set on an otherwise valid request: it must be masked off, not rejected.
        payload = encode_headers([(":method", "GET"), (":path", index_path),
                                  ("host", k.authority)])
        head = (struct.pack(">I", len(payload))[1:] + bytes([REQUEST, END_MESSAGE])
                + struct.pack(">I", 0x800001)[1:])     # R=1, stream 1
        k.send_raw(head + payload)
        status, _, _, _ = k.read_message()
        expect(status == 200, f"reserved bit was not ignored (got {status})")
        k.close()
    c.check("§2", "the reserved bit is ignored, not rejected", ignores_reserved_bit)

    def ignores_unknown_flags():
        k = conn()
        payload = encode_headers([(":method", "GET"), (":path", index_path),
                                  ("host", k.authority)])
        k.send(REQUEST, 0xFF, 1, payload)      # every undefined flag bit set
        status, _, _, _ = k.read_message()
        expect(status == 200, f"undefined flag bits were not ignored (got {status})")
        k.close()
    c.check("§2", "undefined flag bits are ignored", ignores_unknown_flags)

    def oversize_is_skipped_not_fatal():
        k = conn()
        too_big = MAX_PAYLOAD + 1
        k.send_raw(struct.pack(">I", too_big)[1:] + bytes([DATA, 0])
                   + struct.pack(">I", 1)[1:] + b"\0" * too_big)
        status, _, _, _ = k.read_message()
        expect(status == 400, f"oversize frame should be 400, got {status}")
        status, _, _, _ = k.request(index_path, 1)
        expect(status == 200, "connection did not survive an oversize frame")
        k.close()
    c.check("§2", "an oversize frame is 400 and the stream resynchronises",
            oversize_is_skipped_not_fatal)

    # --- §5 Streams ---
    def rejects_bad_stream_ids():
        for bad, why in [(2, "even"), (0, "zero")]:
            k = conn()
            status, _, _, _ = k.request(index_path, bad)
            expect(status == 400, f"{why} stream id should be 400, got {status}")
            k.close()
        k = conn()
        expect(k.request(index_path, 7)[0] == 200, "stream 7 should be fine")
        status, _, _, _ = k.request(index_path, 5)
        expect(status == 400, f"a decreasing stream id should be 400, got {status}")
        k.close()
    c.check("§5", "stream ids must be odd, non-zero and increasing", rejects_bad_stream_ids)

    def ping_is_echoed():
        k = conn()
        k.send(PING, 0, 0, b"\x01\x02\x03\x04")
        ftype, flags, stream, payload = k.read_frame()
        expect(ftype == PING, f"expected a PING back, got type {ftype:#x}")
        expect(flags & ACK, "PING reply did not set ACK")
        expect(stream == 0, f"PING reply used stream {stream}, must be 0")
        expect(payload == b"\x01\x02\x03\x04", "PING payload was not echoed verbatim")
        k.close()
    c.check("§3", "PING is echoed with ACK and the same payload", ping_is_echoed)

    # --- §6 Header blocks ---
    def malformed_block_is_recoverable():
        k = conn()
        # Claims a 9-octet value and supplies two. The frame itself is well formed, so the
        # spec says the connection survives.
        k.send(REQUEST, END_MESSAGE, 1, bytes([0x01, 0x00, 0x09]) + b"hi")
        status, _, _, _ = k.read_message()
        expect(status == 400, f"malformed header block should be 400, got {status}")
        status, _, _, _ = k.request(index_path, 3)
        expect(status == 200, "connection did not survive a malformed header block")
        k.close()
    c.check("§7.1", "a malformed header block is 400 with the connection intact",
            malformed_block_is_recoverable)

    def rejects_undefined_static_index():
        k = conn()
        k.send(REQUEST, END_MESSAGE, 1, bytes([0x0B, 0x00, 0x01]) + b"x")
        status, _, _, _ = k.read_message()
        expect(status == 400, f"static index 11 should be 400, got {status}")
        k.close()
    c.check("§6.1", "an undefined static table index is 400", rejects_undefined_static_index)

    def requires_pseudo_headers():
        k = conn()
        k.send(REQUEST, END_MESSAGE, 1, encode_headers([(":method", "GET")]))
        expect(k.read_message()[0] == 400, "a request with no :path should be 400")
        k.close()
    c.check("§3", "REQUEST without :method/:path/host is 400", requires_pseudo_headers)

    def request_must_set_end_message():
        k = conn()
        k.send(REQUEST, 0, 1, encode_headers([(":method", "GET"), (":path", index_path),
                                              ("host", k.authority)]))
        expect(k.read_message()[0] == 400, "REQUEST without END_MESSAGE should be 400")
        k.close()
    c.check("§3", "REQUEST without END_MESSAGE is 400", request_must_set_end_message)

    # --- §7 Semantics ---
    def not_found():
        k = conn()
        expect(k.request("/definitely-not-here-92731", 1)[0] == 404, "missing file must be 404")
        k.close()
    c.check("§7", "a missing file is 404", not_found)

    def traversal_refused():
        k = conn()
        status, _, _, _ = k.request("/../../etc/passwd", 1)
        expect(status in (403, 404), f"traversal must not be 200, got {status}")
        k.close()
    c.check("§7", "a path escaping the root is refused", traversal_refused)

    def method_not_allowed():
        k = conn()
        expect(k.request(index_path, 1, method="POST")[0] == 405, "POST must be 405")
        k.close()
    c.check("§7", "a method other than GET/HEAD is 405", method_not_allowed)

    def head_sends_no_data():
        k = conn()
        status, headers, body, frames = k.request(index_path, 1, method="HEAD")
        expect(status == 200, f"HEAD should be 200, got {status}")
        expect(frames == 0, f"HEAD sent {frames} DATA frame(s), must send none")
        expect(body == b"", "HEAD returned a body")
        expect(int(dict(headers)["content-length"]) > 0,
               "HEAD must still declare the length a GET would have had")
        k.close()
    c.check("§3", "HEAD ends at the RESPONSE frame with no DATA", head_sends_no_data)

    def client_only_frames_refused():
        k = conn()
        k.send(DATA, END_MESSAGE, 1, b"x")
        expect(k.read_message()[0] == 400, "a client-sent DATA frame should be 400")
        k.close()
    c.check("§3", "a server-only frame type from a client is 400", client_only_frames_refused)

    return c


def main():
    ap = argparse.ArgumentParser(description="LCB/1 conformance tester")
    ap.add_argument("host", nargs="?", default="localhost")
    ap.add_argument("port", nargs="?", type=int, default=9000)
    ap.add_argument("--path", default="/index.html",
                    help="a file that exists in the server's document root")
    args = ap.parse_args()

    print(f"LCB/1 conformance check against {args.host}:{args.port}")
    print("(written from docs/SPEC.md; shares no code with the implementation)\n")
    try:
        c = run(args.host, args.port, args.path)
    except (ConnectionRefusedError, OSError) as e:
        print(f"\ncould not reach {args.host}:{args.port}: {e}")
        return 1

    total = c.passed + len(c.failed)
    print(f"\n{c.passed}/{total} checks passed")
    if c.failed:
        print("\nnot conformant:")
        for section, description, err in c.failed:
            print(f"  {section} {description}: {err}")
        return 1
    print("conformant with LCB/1 version 1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
