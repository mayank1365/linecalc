# Submission

**Mayank Gupta · 23BCS10069 · mayank.23bcs10069@sst.scaler.com**
Network Architecture — *Build a calculator that stays on the line*

Build and verify everything:

```bash
mvn -q package                            # 72 tests
./httpcalc 8080 &                         # part 1
python3 tests/persistent_socket_check.py  # the marking procedure, reproduced
./bserve ./www 9000 &                     # part 2
./bcurl -v localhost:9000/index.html      # body to stdout, hexdump to stderr
```

---

## Page 1 — the persistent calculator

| Required | Where |
|---|---|
| Any language, no framework, just a socket | Java 17, `java.net.Socket` only. JUnit is test-scope. |
| `GET /add?a=2&b=3` → `200 5` | `HttpCalcConnection.handle` |
| `GET /sub?a=10&b=4` → `200 6` | same |
| `GET /mul?a=6&b=7` → `200 42` | same |
| `GET /div?a=9&b=3` → `200 3` | same |
| `GET /div?a=1&b=0` → `400` | `Operation.DIV` |
| `GET /add?a=x&b=3` → `400` | `Calculator.operand` |
| `GET /pow?a=2&b=8` → `404` | path checked before method |
| `POST /add` → `405` | `Allow: GET, HEAD`, body still drained |
| `GET /add` with no `Host` → `400` | `HttpRequestParser`, framing intact so the connection survives |
| **One socket, every request, still open** | `tests/persistent_socket_check.py`, `HttpCalcServerTest.oneSocketServesEveryRequestInTheAssignment` |
| Consume exactly `Content-Length` and not one more | `HttpRequestParser` + `Bytes.readExactly`; pinned by `stopsOnTheLastBodyByteSoTheNextRequestIsIntact` |

Optional stretch goals — **all four done**: `Connection: close`, a defended 30s idle timeout,
chunked request decoding, and pipelining (all six written before any response is read).

Observed output of the marking script:

```
socket still open: True
1 TCP handshake, 10 responses
```

## Page 2 — the binary protocol

| Required | Where |
|---|---|
| Track 1 — `./bserve ./www 9000` | `linecalc.server.BinaryServer` |
| accept, read a frame, map path under a root, reply status + headers + bytes | `BinaryConnection`, `FileStore` |
| `404` if it is not there | `FileStore.resolve` (plus `403` for escaping the root) |
| `400` if the frame is malformed | `FrameCodec`, `HeaderCodec` |
| and keep the connection open | `BinaryServerTest.keepsTheConnectionOpenAcrossManyRequests` |
| Track 2 — `./bcurl -v localhost:9000/index.html` | `linecalc.client.BinaryClient` |
| build the request frame, body to stdout, `-v` hexdumps every frame | body → stdout, diagnostics → stderr |
| exit non-zero on 4xx / 5xx | `4` and `5`, distinguished |
| **never open a second connection** | one `Socket` in `run()`; a second host is a usage error |
| **Fixed-size frame header, fields and widths defended** | `docs/SPEC.md` §2.1, README "The frame header" |
| **Number the ten names you send, length-prefix the rest** | `StaticTable`, `HeaderCodec` — HPACK's first two mechanisms |
| **Unknown frame type MUST be skipped cleanly** | `FrameCodec.read`/`readKnown`; `FrameCodecTest.skipsAnUnknownFrameTypeCleanlyAndKeepsReading`, `BinaryServerTest.skipsUnknownFrameTypesAndKeepsServing` |

### What you hand in

1. **The spec, enough for a stranger** — [`docs/SPEC.md`](docs/SPEC.md), nine sections
   including a conformance checklist.
2. **The program** — `bserve` and `bcurl`, plus `httpcalc` for page 1.
3. **An annotated hexdump of one complete request and response** —
   [`docs/annotated-frame.md`](docs/annotated-frame.md), every octet, with the field arithmetic
   checked against the declared lengths.

---

## The header widths, in one paragraph

HTTP/2 chose 24 / 8 / 8 / 1+31 and landed on a nine-octet header. Nine straddles every
alignment boundary, so the header never loads as an aligned machine word. LCB/1 keeps the
first three fields and spends **23** bits on the stream id instead of 31, giving a header of
exactly **eight** octets. The cost is a ceiling of 8.4M never-reused stream ids per
connection — about two and a half hours at 1,000 requests per second, after which a client
opens a new connection. Full argument in `docs/SPEC.md` §2.1.

## The line that may not be skipped

> A receiver meeting a frame type it does not understand MUST discard exactly `Length` octets
> and continue.

The length prefix sits at a fixed offset in every frame, understood or not, so a receiver can
always find a frame's end without knowing its meaning. That is what lets a version 2 ship
without upgrading every peer on the same day — and it is why the reserved bit and the
undefined flag bits are specified as *ignored* rather than *invalid*.
