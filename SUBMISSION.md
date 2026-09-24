# Submission

**Mayank Gupta · 23BCS10069 · mayank.23bcs10069@sst.scaler.com**

## 1. Project

- **Repository:** linecalc
- **Language:** Java 17
- **Dependencies/frameworks:** None (JUnit 5 is test-scope only)
- **Build tool:** Maven

## 2. What Was Implemented

### Track 1 — Persistent HTTP Calculator

| Requirement | Status | Where |
|---|---|---|
| TCP server | Done | `server/HttpCalcServer.java` |
| HTTP/1.1-style request parsing | Done | `protocol/HttpRequestParser.java` |
| ADD, SUB, MUL, DIV | Done | `calculator/Operation.java` |
| 400 handling | Done | bad operand, division by zero, missing `Host` |
| 404 handling | Done | unknown path, e.g. `/pow` |
| 405 handling | Done | `POST /add`, with `Allow: GET, HEAD` |
| Content-Length based request framing | Done | `Bytes.readExactly` — exactly n octets, never n+1 |
| Persistent TCP connection | Done | `server/HttpCalcConnection.java` |
| Multiple requests over the same socket | Done | `tests/persistent_socket_check.py` |

Optional extras, all implemented: `Connection: close`, 30s idle timeout, chunked request
decoding, pipelining.

### Track 2 — Binary Protocol

| Requirement | Status | Where |
|---|---|---|
| Custom binary request/response protocol | Done | `docs/SPEC.md` |
| Fixed-size frame header | Done | 8 octets — length 24, type 8, flags 8, R 1, stream 23 |
| Length-prefixed payloads | Done | `protocol/FrameCodec.java`, `protocol/HeaderCodec.java` |
| Binary file server (`bserve`) | Done | `server/BinaryServer.java` |
| Binary client (`bcurl`) | Done | `client/BinaryClient.java` |
| 400 malformed-frame handling | Done | `FrameCodec.read`, `HeaderCodec.decode` |
| 404 file-not-found handling | Done | `server/FileStore.java` (plus 403 for root escapes) |
| Unknown frame type skipping | Done | `FrameCodec.read` / `readKnown` |
| Persistent connections | Done | server keeps serving; `bcurl` never opens a second socket |

Header names are numbered against a ten-entry static table; anything outside it is sent as a
length-prefixed literal.

## 3. Running

```bash
mvn -q package                          # build

./httpcalc 8080                         # Track 1
curl "http://localhost:8080/add?a=2&b=3"

./bserve ./www 9000                     # Track 2
./bcurl -v localhost:9000/index.html
```

## 4. Testing

```bash
mvn -o test                                 # 130 JUnit tests, all passing
python3 tests/persistent_socket_check.py    # the marking procedure
```

The marking script opens **one** socket, issues every request from the assignment, and reports:

```
socket still open: True
1 TCP handshake, 10 responses
```

## 5. Protocol

See:

- `docs/SPEC.md` — the complete wire format, including a conformance checklist
- `docs/annotated-frame.md` — one complete request and response, every octet annotated

## 6. Example

Request (`GET /hello.txt`), as sent by `bcurl`:

```
0000  00 00 30 01 01 00 00 01  01 00 03 47 45 54 02 00  |..0........GET..|
0010  0a 2f 68 65 6c 6c 6f 2e  74 78 74 04 00 0e 6c 6f  |./hello.txt...lo|
0020  63 61 6c 68 6f 73 74 3a  39 30 30 30 07 00 09 62  |calhost:9000...b|
0030  63 75 72 6c 2f 31 2e 30                           |curl/1.0|
```

`00 00 30` = payload length 48 · `01` = `REQUEST` · `01` = `END_MESSAGE` · `00 00 01` = stream 1.
Then the header block: `01` → `:method` = `GET`, `02` → `:path` = `/hello.txt`,
`04` → `host` = `localhost:9000`, `07` → `user-agent` = `bcurl/1.0`.

Response — a `RESPONSE` frame carrying the status and headers, then a `DATA` frame carrying the
body with `END_MESSAGE` set:

```
0000  00 00 5d 02 00 00 00 01  03 00 03 32 30 30 ...    |..]........200..|   RESPONSE
0000  00 00 0f 03 01 00 00 01  68 65 6c 6c 6f 2c 20 66  |........hello, f|   DATA
0010  72 61 6d 69 6e 67 0a                              |raming.|
```

Full annotation in `docs/annotated-frame.md`.
