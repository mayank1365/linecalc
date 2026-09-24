# linecalc

**Network Architecture assignment — "Build a calculator that stays on the line"**
Mayank Gupta · 23BCS10069 · mayank.23bcs10069@sst.scaler.com

Two exercises in one repository, joined by a single idea: **when a connection stays open, you
have to say where each message ends.**

| | Part | What it is | Command |
|---|---|---|---|
| 1 | **Persistent HTTP/1.1 calculator** | One TCP connection, many requests, framed by `Content-Length` | `./httpcalc 8080` |
| 2 | **LCB/1 — a binary HTTP-like protocol** | Fixed 8-octet frame header, length-prefixed everything, server + client | `./bserve ./www 9000`, `./bcurl -v localhost:9000/index.html` |

Java 17, Maven, **no web framework** — `java.net.Socket` and `java.io` only. The single
non-production dependency is JUnit 5.

---

## Table of contents

- [Quick start](#quick-start)
- [Part 1 — the persistent calculator](#part-1--the-persistent-calculator)
- [Part 2 — LCB/1, the binary protocol](#part-2--lcb1-the-binary-protocol)
- [Repository layout](#repository-layout)
- [Testing](#testing)
- [Design decisions, and why](#design-decisions-and-why)
- [Limits and what a version 2 would add](#limits-and-what-a-version-2-would-add)

---

## Quick start

```bash
mvn -q package          # compiles and runs all 130 tests
```

Then either half:

```bash
# Part 1 — the calculator
./httpcalc 8080 &
curl "http://localhost:8080/add?a=2&b=3"        # -> 5
python3 tests/persistent_socket_check.py 8080   # the marking procedure, reproduced

# Part 2 — the binary protocol
./bserve ./www 9000 &
./bcurl -v localhost:9000/index.html            # body to stdout, hexdumps to stderr
```

The three wrapper scripts (`httpcalc`, `bserve`, `bcurl`) just put `target/classes` on the
classpath and pick a main class; run `mvn package` first and they will tell you if you forgot.

---

## Part 1 — the persistent calculator

### Endpoints

`./httpcalc [port]`, default **8080**. Operands are 64-bit signed integers.

| Request | Status | Body | Why |
|---|---|---|---|
| `GET /add?a=2&b=3` | `200` | `5` | |
| `GET /sub?a=10&b=4` | `200` | `6` | |
| `GET /mul?a=6&b=7` | `200` | `42` | |
| `GET /div?a=9&b=3` | `200` | `3` | Integer division, truncated toward zero |
| `GET /div?a=1&b=0` | `400` | | Division by zero is not a computation |
| `GET /add?a=x&b=3` | `400` | | `x` is not an integer |
| `GET /pow?a=2&b=8` | `404` | | No such operation |
| `POST /add` | `405` | | Path exists, method does not (`Allow: GET, HEAD`) |
| `GET /add` *(no `Host`)* | `400` | | HTTP/1.1 requires `Host` |

Success bodies are the bare number with **no trailing newline**, so a client can compare them
byte for byte. Error bodies are a short `text/plain` explanation.

Overflow is a `400` rather than a silent wraparound: `add?a=9223372036854775807&b=1` has no
64-bit answer, and quietly returning `-9223372036854775808` would be a wrong answer dressed as
a right one.

### The part that is actually hard

HTTP/1.0 could answer *"where does this request end?"* with *"at EOF"* — the close told you,
for free. Keeping the connection open takes that away, and the boundary has to be derived
instead. Get it wrong by one byte and byte n+1, which belongs to the next request, is parsed
as though it were part of this one.

So `HttpRequestParser` never reads ahead:

- the request line and headers are read **one byte at a time** until CRLF. Any bulk read could
  pull body octets — or the first octets of a pipelined request — into a private buffer where
  the next parse will not find them;
- the body is then read as **exactly `Content-Length` octets** via `Bytes.readExactly`, or
  decoded chunk by chunk for `Transfer-Encoding: chunked`;
- the stream is created **once per connection** and reused across every request on it. Building
  a fresh `BufferedInputStream` per request is the classic way to silently drop pipelined bytes
  that are already buffered.

A body is drained even when the response ignores it. `POST /add` answers `405` without looking
at the body, but the octets are still consumed — otherwise they would be read as the next
request line. `HttpCalcServerTest.postBodyIsDrainedSoTheNextRequestIsNotCorrupted` pins this.

### Optional extras, all implemented

| Stretch goal | Status |
|---|---|
| `Connection: close` | Honoured, and answered with `Connection: close` before closing. HTTP/1.0 gets the inverse default — close unless `Connection: keep-alive`. |
| Idle timeout | 30s via `SO_TIMEOUT`, then a silent close. |
| Chunked encoding | Decoded on requests, including chunk extensions and the trailer section. |
| Pipelining | All six requests can be written before any response is read; answers come back in order. |

**Defending the idle timeout.** Something must bound it, or one idle client holds a thread and
a file descriptor forever — a denial of service you inflicted on yourself. 30s sits between
Apache's 5s and nginx's 75s. The close is *silent* rather than a `408`: a client that has not
started a request has no outstanding read to see the status line with, and one that is
mid-request will see the close and retry, which is what RFC 9112 §9.6 tells it to do anyway.

**Framing lost vs. framing intact.** Not every `400` is equal, and `HttpException` carries the
difference:

- *Framing intact* — the request was fully read before we objected (a missing `Host`, a bad
  operand). We answer and **keep the connection open**.
- *Framing lost* — we failed mid-head, so we no longer know where this request stops. We answer
  and **close**, because guessing would corrupt whatever comes next.

`Content-Length` and `Transfer-Encoding` arriving together is a flat `400`. Two different
answers to "where does this end" is the request-smuggling primitive, not an ambiguity to be
resolved by preferring one.

---

## Part 2 — LCB/1, the binary protocol

Full wire format: **[`docs/SPEC.md`](docs/SPEC.md)**.
Every octet of one exchange: **[`docs/annotated-frame.md`](docs/annotated-frame.md)**.

### The frame header

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-----------------------------------------------+---------------+
|                Payload Length (24)             |    Type (8)   |
+---------------+-+-----------------------------------------------+
|   Flags (8)   |R|                Stream ID (23)                 |
+---------------+-+-----------------------------------------------+
|                    Payload (Length octets)                    ...
```

**Eight octets — and the widths are the assignment's actual question.** HTTP/2 chose
24 / 8 / 8 / 1+31, which totals nine. Nine is the one size in that neighbourhood with no
redeeming property: every header after the first straddles an 8-octet boundary, so it never
loads as an aligned machine word and a struct mapped over it needs explicit packing. Keeping
HTTP/2's first three fields and spending **23** bits on the stream id instead of 31 buys a
header of exactly eight octets.

| Field | Width | Defence |
|---|---|---|
| Length | 24 | 16 bits caps a frame at 64 KiB and shatters a large response into thousands of frames. 32 bits lets a stranger announce a 4 GiB allocation in the first four octets it ever sends. 24 is the compromise — then a *policy* cap of 64 KiB sits far below the structural one. |
| Type | 8 | Four used, 252 left as the version-2 budget. The skip rule below is what makes that budget spendable. |
| Flags | 8 | One used (`END_MESSAGE`, reused as `ACK` on `PING`). A per-frame boolean that would otherwise cost a payload octet and a parse step. |
| R | 1 | Reserved **and specified as ignored**. A bit receivers must ignore is a bit a later version can use; a bit version 1 validated strictly would be permanently dead. |
| Stream ID | 23 | 8.4M ids per connection. Ids are never reused, so this is a budget, not a concurrency ceiling — 1,000 req/s exhausts it in ~2h20m, then you open a new connection. Cheap price for the aligned header. |

### Frame types

| Code | Name | Direction | Payload |
|---|---|---|---|
| `0x01` | `REQUEST` | client → server | Header block |
| `0x02` | `RESPONSE` | server → client | Header block |
| `0x03` | `DATA` | server → client | Body octets |
| `0x04` | `PING` | either | 0–8 opaque octets, echoed with `ACK` |

### Header compression — HPACK's first two mechanisms

Ten names are numbered, so `content-length` costs **one octet instead of fifteen**. Anything
else travels as a length-prefixed literal, so the table being short costs bytes and never
correctness.

```
+---------------+
|  Name code (8)|   1..10 = static table index,  0 = a literal name follows
+---------------+
| Name len (8)  |   \  only when the code is 0
| Name (len)    |   /
+---------------+
| Value len(16) |   always
| Value (len)   |
+---------------+
```

There is no field count — the block runs to the end of the payload, whose length the frame
header already stated. A count would be a second source of truth about one fact, and two
sources of truth can disagree.

`:status` travels as ASCII `"200"`, not a 16-bit integer. That costs two octets and buys the
rule that *every* value is a length-prefixed byte string, so a reader can step over any field
without knowing what it is.

**Not implemented, deliberately:** HPACK's dynamic table. It is where the real compression
ratio lives, and also where HPACK gets hard — both peers must evolve byte-identical tables in
lockstep, and it is what made CRIME-style attacks possible. Version 1 stays stateless.

### The one line that may not be skipped

> A receiver meeting a frame type it does not understand **MUST** discard exactly `Length`
> octets and carry on — not close, not error, not guess.

This is the difference between a protocol and a format. Because the length prefix sits at a
fixed offset in *every* frame, a receiver can always find a frame's end without understanding
its meaning, so a version-2 peer can talk to a version-1 peer and be stepped over politely
instead of killing the connection. Without it, every extension is a flag day.

The same reasoning is why `R` and undefined flag bits are specified as **ignored** rather than
invalid: strict validation of a field nobody uses yet is a decision never to use it.

Implemented in `FrameCodec` (`read` hands unknown frames back so they can be logged, `readKnown`
drops them silently), exercised by `FrameCodecTest.skipsAnUnknownFrameTypeCleanlyAndKeepsReading`
and, over a real socket, `BinaryServerTest.skipsUnknownFrameTypesAndKeepsServing`.

### `bserve` — the server

```
./bserve <document-root> [port]          # default 9000
```

Accepts a connection, reads the 4-octet `LCB1` preface, then serves frames until the peer
leaves. Maps `:path` to a file under the root, replies with a `RESPONSE` frame and then `DATA`
frames of 16 KiB, and **keeps the connection open**.

| Status | When |
|---|---|
| `200` | Found and sent |
| `400` | Malformed frame or header block, bad stream id, `REQUEST` without `END_MESSAGE` |
| `403` | Path escapes the document root |
| `404` | No such file, or a dotfile |
| `405` | Method is not `GET` or `HEAD` |
| `500` | Failed while reading a file it had already found |

Path containment is checked **structurally**: normalise, then resolve symlinks, then ask
whether the result is still under the root. Filtering the raw path for `".."` is what everyone
reaches for first and it loses to percent-encoding, to `....//`, and to a symlink inside the
root pointing out of it.

### `bcurl` — the client

```
./bcurl [-v] [-I] <host:port/path> [more paths on the same host...]
  -v   hexdump every frame, both directions (stderr)
  -I   send HEAD instead of GET
```

| Exit | Meaning |
|---|---|
| `0` | 2xx |
| `1` | Transport or protocol error |
| `2` | Usage error |
| `4` | 4xx |
| `5` | 5xx |

`4` and `5` are distinguished rather than both being `1`, so a script can tell "I asked for the
wrong thing" from "the server broke".

**"Never open a second connection" is structural, not promised.** `run()` opens exactly one
`Socket`; every URL on the command line goes down it on its own odd, increasing stream id. A
URL naming a different host or port is a *usage error*, not a second dial:

```console
$ ./bcurl localhost:9000/a.html localhost:9001/b.html
bcurl: localhost:9001 is not localhost:9000; that would need a second connection,
       which this client does not open
```

The body goes to **stdout** and every diagnostic to **stderr**, so
`./bcurl host:9000/photo.png > photo.png` writes the file and not the file plus commentary.

---

## Repository layout

```
linecalc/
├── src/main/java/linecalc/
│   ├── server/      HttpCalcServer + Connection   (part 1)
│   │                BinaryServer + Connection, FileStore  (part 2, bserve)
│   ├── client/      BinaryClient                  (part 2, bcurl)
│   ├── protocol/    Http{Request,Response,RequestParser,Exception}   — HTTP/1.1
│   │                Frame, FrameType, FrameCodec                     — LCB/1 framing
│   │                HeaderCodec, HeaderField, StaticTable            — LCB/1 headers
│   ├── calculator/  Calculator, Operation, CalcException  (no sockets in here)
│   └── common/      Bytes (readExactly / skipExactly), Hex, Log
├── tests/
│   ├── java/linecalc/…            130 JUnit 5 tests
│   └── persistent_socket_check.py the marking procedure, reproduced
├── www/                           document root for bserve
├── docs/
│   ├── SPEC.md                    the LCB/1 wire format, for a stranger
│   └── annotated-frame.md         one exchange, every octet annotated
├── httpcalc, bserve, bcurl        wrapper scripts
└── pom.xml
```

`calculator/` knows nothing about sockets, headers or status codes — the arithmetic is not the
point of the assignment, so it stays behind one pure function and gets out of the way.

---

## Testing

```bash
mvn -o test                              # 130 JUnit tests
python3 tests/persistent_socket_check.py # the assignment's own marking procedure
```

Tests live in `tests/java/` rather than `src/test/java/`, so `pom.xml` points
`testSourceDirectory` there.

| Suite | Tests | Covers |
|---|---|---|
| `CalculatorTest` | 8 | Arithmetic, division by zero, non-integers, overflow, path mapping |
| `HttpRequestParserTest` | 13 | Exact `Content-Length` framing, pipelining, chunked, `Host`, smuggling, malformed heads |
| `HttpCalcServerTest` | 8 | All nine assignment cases on **one socket**, pipelining, body draining, `Connection: close`, `HEAD` |
| `FrameCodecTest` | 12 | Header layout, **unknown-type skipping**, ignored reserved bit and flags, oversize skip, truncation |
| `HeaderCodecTest` | 15 | Static indices, literals, UTF-8, round-trips, every malformed-block case |
| `BinaryServerTest` | 16 | End-to-end over a socket: 200/400/403/404/405, **skip-and-keep-serving**, stream ids, `PING`, `HEAD`, multi-frame bodies, bad preface |
| `FileStoreTest` | 12 | Path containment directly: `..` in every spelling, **symlinks pointing out of the root**, dotfiles, content types |
| `HttpResponseTest` | 10 | Status line, CRLF, `Content-Length` in octets not characters, IMF-fixdate, `HEAD` |
| `BytesTest` | 11 | `readExactly` across short reads, `skipExactly` past its sink buffer, unsigned widths at their boundaries |
| `HexTest` | 7 | Row splitting, alignment, non-printable substitution — the hexdump is itself a deliverable |
| `BinaryClientTest` | 7 | URL parsing, default port, endpoint comparison |
| `InteropTest` | 11 | **The real client against the real server**: multi-request connections, exit codes, reassembly, 12 concurrent clients |

Every suite above drives one side with a hand-built peer, which only proves each side matches
*my* reading of the spec. `InteropTest` runs the actual `bcurl` against the actual `bserve`,
which is the claim that matters to anyone writing a third implementation.

`persistent_socket_check.py` is the grading script from the assignment, written out literally —
one `socket.create_connection`, every request, and then:

```
socket still open: True
1 TCP handshake, 10 responses
```

---

## Design decisions, and why

**Framing is a property of the connection, not the message.** Both halves of this repository
are the same program written twice. HTTP/1.1 puts the length in a header you have to find by
scanning for a blank line; LCB/1 puts it in a fixed 24-bit field you cannot miss. That is the
entire difference, and it is why HTTP/1.1 has a request-smuggling literature and HTTP/2 does
not.

**Two kinds of error, everywhere.** Both `HttpException` and `ProtocolException` carry
`framingIntact()`. A failure that leaves the stream aligned is answered and survived; a failure
that loses the boundary is answered and closed. Collapsing these into "it's a 400" either
closes connections that were fine or keeps reading a stream that is now garbage being parsed
as structure.

**Reserved fields are specified as ignored, not validated.** `R` and the undefined flag bits
MUST be ignored on receipt. This is the only thing that keeps them available to a version 2 —
a peer that rejects unknown bits today is a peer that must be upgraded before anyone can use
them tomorrow.

**Limits are policy, not structure.** The 24-bit length field permits 16 MiB; version 1
accepts 64 KiB. The structural maximum is what the format can express, the policy maximum is
what we are willing to allocate for a stranger, and they should not be the same number.

**Stream ids exist before multiplexing does.** Version 1 sends one request at a time, so the
field does nothing yet. It is there because retrofitting request/response correlation onto a
protocol that assumed strict ordering is precisely the mistake HTTP/1.1 pipelining made, and
it cost the web fifteen years of head-of-line blocking.

---

## Limits and what a version 2 would add

Version 1 is deliberately small. Known limits, all of them chosen rather than overlooked:

- **Not multiplexed.** One request in flight per connection. The stream id is already on the
  wire, so interleaving is an implementation change, not a format change.
- **No request bodies.** `REQUEST` must set `END_MESSAGE`. `POST` needs client-to-server `DATA`
  frames, which is a version-2 job.
- **No dynamic header table.** See above — stateless by choice.
- **No flow control.** A server can outrun a slow client until the kernel buffer pushes back.
  HTTP/2 spends a whole frame type (`WINDOW_UPDATE`) on this; version 1 has a spare type code
  and the room to add it.
- **No TLS.** Out of scope for the assignment.
- **Whole files are read into memory** before the first `DATA` frame goes out. Fine for a
  document root of web pages, wrong for large files; streaming would change `BinaryConnection`
  and nothing about the wire format.

A version 2 can add any of these without breaking a version-1 peer, because of exactly one
sentence in the spec: a receiver meeting a frame type it does not know skips it cleanly.
