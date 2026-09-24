# LineCalc

A persistent HTTP/1.1 calculator and a custom binary HTTP-like protocol, written directly on
TCP sockets with no web framework.

## Overview

Both halves of this project demonstrate the same four things:

- **TCP sockets** — `java.net.Socket` and `java.io` only, no framework
- **Persistent connections** — one TCP handshake serves many requests
- **Request framing** — where one message ends and the next begins, derived rather than
  guessed
- **A custom binary protocol** — a fixed-size frame header with length-prefixed payloads

The second point is what forces the third. While a connection closes after every response,
"where does this message end?" is answered for free, at EOF. Once the connection stays open
you have to say so explicitly — with `Content-Length` in Track 1, and with a 24-bit length
field in Track 2.

## Architecture

```
                 Track 1                             Track 2

              bcurl / curl                            bcurl
                   |                                    |
            one TCP socket                       one TCP socket
                   |                                    |
             HttpCalcServer                       BinaryServer
                   |                                    |
          HttpRequestParser                         FrameCodec
       (read exactly Content-Length)          (read exactly Length)
                   |                                    |
               Calculator                           HeaderCodec
                   |                                    |
              HttpResponse                          FileStore
                   |                                    |
             status + headers                    RESPONSE + DATA frames
                   |                                    |
                   +--------- same socket ---------------+
                               (stays open)
```

## Features

### HTTP Calculator

`./httpcalc [port]` — default **8080**. Operands are 64-bit signed integers.

| Request | Status | Body |
|---|---|---|
| `GET /add?a=2&b=3` | `200` | `5` |
| `GET /sub?a=10&b=4` | `200` | `6` |
| `GET /mul?a=6&b=7` | `200` | `42` |
| `GET /div?a=9&b=3` | `200` | `3` |
| `GET /div?a=1&b=0` | `400` | division by zero |
| `GET /add?a=x&b=3` | `400` | operand is not an integer |
| `GET /pow?a=2&b=8` | `404` | no such operation |
| `POST /add` | `405` | `Allow: GET, HEAD` |
| `GET /add` *(no `Host`)* | `400` | HTTP/1.1 requires `Host` |

- **Persistent connection** — every request above can be served on one socket, which is still
  open afterwards
- **`Content-Length` framing** — the head is read one byte at a time, the body as exactly
  `Content-Length` octets, and never one more
- **`Connection: close`** honoured, with the HTTP/1.0 default inverted
- **Chunked** request bodies decoded
- **Pipelining** — all six requests may be written before any response is read; answers come
  back in order
- **30s idle timeout**, then a silent close

Success bodies are the bare number with no trailing newline, so they can be compared byte for
byte.

### Binary Protocol (LCB/1)

`./bserve <root> [port]` and `./bcurl [-v] [-I] <host:port/path>` — default port **9000**.

Every frame is an 8-octet header followed by exactly `Length` octets:

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

| Type | Name | Direction |
|---|---|---|
| `0x01` | `REQUEST` | client → server |
| `0x02` | `RESPONSE` | server → client |
| `0x03` | `DATA` | server → client |
| `0x04` | `PING` | either |

- **Custom frame format** — fixed 8-octet header; the field widths are defended in
  `docs/SPEC.md` §2.1
- **Length-prefixed payloads** — the 24-bit length is at a fixed offset in every frame
- **Header compression** — ten header names are numbered, everything else is a
  length-prefixed literal
- **File serving** — paths map under a document root, with `403` for anything escaping it
- **Persistent connection** — the server keeps serving, and `bcurl` never opens a second
  socket
- **Unknown frame types are skipped cleanly** — a receiver discards exactly `Length` octets
  and carries on rather than failing
- **`400`** for a malformed frame, **`404`** for a missing file, **`405`** for a method other
  than `GET`/`HEAD`

`bcurl` exit codes: `0` on 2xx, `4` on 4xx, `5` on 5xx, `2` on usage, `1` on transport error.
The body goes to stdout and every diagnostic to stderr, so `./bcurl host:9000/f > f` works.

## Project Structure

```
linecalc/
├── src/main/java/linecalc/
│   ├── server/       HttpCalcServer, BinaryServer, FileStore
│   ├── client/       BinaryClient (bcurl)
│   ├── protocol/     HTTP/1.1 messages; LCB/1 frames and header blocks
│   ├── calculator/   arithmetic, with no knowledge of sockets
│   └── common/       Bytes (readExactly / skipExactly), Hex, Log
├── tests/
│   ├── java/                        130 JUnit 5 tests
│   └── persistent_socket_check.py   the assignment's marking procedure
├── www/                             document root for bserve
├── docs/
│   ├── SPEC.md                      the LCB/1 wire format
│   └── annotated-frame.md           one exchange, every octet annotated
├── httpcalc, bserve, bcurl          launcher scripts
├── README.md
├── SUBMISSION.md
└── pom.xml
```

## Build

```bash
mvn -q package
```

Java 17, Maven, no runtime dependencies. Run this before the scripts below — they put
`target/classes` on the classpath.

## Run

### Calculator Server

```bash
./httpcalc 8080
```

### Binary Server

```bash
./bserve ./www 9000
```

### Binary Client

```bash
./bcurl -v localhost:9000/index.html
```

`-v` hexdumps every frame in both directions; `-I` sends `HEAD` instead of `GET`.

## Examples

### Calculator, two requests on one socket

```console
$ ./httpcalc 8080 &
$ curl "http://localhost:8080/add?a=2&b=3"
5
$ curl "http://localhost:8080/div?a=1&b=0" -o /dev/null -w '%{http_code}\n'
400
```

Request and response on the wire:

```http
GET /add?a=2&b=3 HTTP/1.1
Host: localhost:8080

HTTP/1.1 200 OK
Date: Mon, 21 Sep 2026 18:46:48 GMT
Server: linecalc/1.0
Content-Type: text/plain; charset=utf-8
Connection: keep-alive
Keep-Alive: timeout=30
Content-Length: 1

5
```

### Binary protocol

```console
$ ./bserve ./www 9000 &
$ ./bcurl -v localhost:9000/hello.txt
> REQUEST len=48 flags=0x01 stream=1
>   :method: GET
>   :path: /hello.txt
>   host: localhost:9000
0000  00 00 30 01 01 00 00 01  01 00 03 47 45 54 02 00  |..0........GET..|
0010  0a 2f 68 65 6c 6c 6f 2e  74 78 74 04 00 0e 6c 6f  |./hello.txt...lo|
...
< RESPONSE len=93 flags=0x00 stream=1
<   :status: 200
<   content-type: text/plain; charset=utf-8
<   content-length: 15
< DATA len=15 flags=0x01 stream=1
0000  00 00 0f 03 01 00 00 01  68 65 6c 6c 6f 2c 20 66  |........hello, f|
0010  72 61 6d 69 6e 67 0a                              |raming.|
hello, framing
```

Reading the request header: `00 00 30` is a payload length of 48, `01` is `REQUEST`, `01` is
`END_MESSAGE`, and `00 00 01` is stream 1 with the reserved bit clear. Every octet of this
exchange is broken down in `docs/annotated-frame.md`.

Several paths on **one** connection:

```console
$ ./bcurl localhost:9000/index.html localhost:9000/hello.txt
```

A URL naming a different host or port is a usage error rather than a second connection.

## Testing

```bash
mvn -o test                                 # 130 JUnit tests
python3 tests/persistent_socket_check.py    # the assignment's marking procedure
```

Tests live in `tests/java/`, so `pom.xml` points `testSourceDirectory` there.

| Suite | Tests | Covers |
|---|---|---|
| `CalculatorTest` | 8 | Arithmetic, division by zero, non-integers, overflow |
| `HttpRequestParserTest` | 13 | `Content-Length` framing, pipelining, chunked, `Host` |
| `HttpCalcServerTest` | 8 | All nine cases on one socket, pipelining, body draining |
| `HttpResponseTest` | 10 | Status line, CRLF, `Content-Length` in octets, `HEAD` |
| `FrameCodecTest` | 12 | Header layout, unknown-type skipping, truncation |
| `HeaderCodecTest` | 15 | Static indices, literals, UTF-8, malformed blocks |
| `BinaryServerTest` | 16 | 200/400/403/404/405 over a socket, stream ids, `PING` |
| `FileStoreTest` | 12 | Path containment, symlink escapes, dotfiles |
| `BinaryClientTest` | 7 | URL parsing, endpoint comparison |
| `InteropTest` | 11 | The real client against the real server; 12 concurrent clients |
| `BytesTest` | 11 | `readExactly` across short reads, `skipExactly`, unsigned widths |
| `HexTest` | 7 | Hexdump row splitting and alignment |

`persistent_socket_check.py` reproduces the marking procedure — one
`socket.create_connection`, every request from the assignment, and then:

```
socket still open: True
1 TCP handshake, 10 responses
```

## Protocol

For the complete protocol specification:

**[`docs/SPEC.md`](docs/SPEC.md)**

For the annotated real request/response:

**[`docs/annotated-frame.md`](docs/annotated-frame.md)**
