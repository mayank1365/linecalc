# One complete exchange, every octet accounted for

Captured from the reference implementation. To reproduce:

```
$ ./bserve ./www 9000 &
$ ./bcurl -v localhost:9000/hello.txt
```

`www/hello.txt` is the 15 octets `hello, framing\n`. The only octets that will differ on your
machine are the `date` header's, which changes the `RESPONSE` frame's length accordingly.

Reading the hexdumps: the left column is the offset **within that frame**, then 16 octets of
hex, then the same 16 octets as printable ASCII with `.` for everything else.

---

## 0. Connection preface — 4 octets, once

```
0000  4c 43 42 31                                       |LCB1|
```

| Octets | Value | Meaning |
|---|---|---|
| `4c 43 42 31` | `"LCB1"` | Protocol and version. Sent by the client before its first frame. |

A server that reads anything else here hangs up without replying — the peer is speaking some
other protocol and a reply would only add noise. Four octets, once per connection, in exchange
for turning every "why is this parse failing" into one unambiguous answer.

---

## 1. Client → server: `REQUEST` — 56 octets

```
0000  00 00 30 01 01 00 00 01  01 00 03 47 45 54 02 00  |..0........GET..|
0010  0a 2f 68 65 6c 6c 6f 2e  74 78 74 04 00 0e 6c 6f  |./hello.txt...lo|
0020  63 61 6c 68 6f 73 74 3a  39 30 30 30 07 00 09 62  |calhost:9000...b|
0030  63 75 72 6c 2f 31 2e 30                           |curl/1.0|
```

### 1.1 Frame header — offsets `0x00`–`0x07`

| Offset | Octets | Field | Value |
|---|---|---|---|
| `0x00` | `00 00 30` | Payload Length (24) | `0x000030` = **48**. The header itself is not counted. |
| `0x03` | `01` | Type (8) | `0x01` = **`REQUEST`** |
| `0x04` | `01` | Flags (8) | `0x01` = **`END_MESSAGE`**. Version 1 has no request bodies, so every `REQUEST` sets it. |
| `0x05` | `00 00 01` | R (1) + Stream ID (23) | Top bit `0` is the reserved bit; the remaining 23 are stream **1**. Odd and non-zero, as a client-initiated stream must be. |

Eight octets, and at this point the receiver already knows exactly where this frame ends —
`0x08 + 48 = 0x38` — **without understanding a single thing about its contents**. That is the
whole trick. It is what lets a receiver skip a frame type it has never heard of (§4 of the
spec), and it is what `Content-Length` does for HTTP/1.1, hoisted into a fixed position so it
can never be missing, misspelled, or duplicated.

### 1.2 Payload — a header block, offsets `0x08`–`0x37`

Four fields packed back to back. There is no field count anywhere: the block simply runs to
the end of the payload, whose length the frame header already gave.

```
0008  01 00 03 47 45 54                                  :method: GET
```
| Octets | Meaning |
|---|---|
| `01` | Static table index 1 → `:method`. One octet instead of seven. |
| `00 03` | Value length, 16 bits = 3 |
| `47 45 54` | `GET` |

```
000e  02 00 0a 2f 68 65 6c 6c 6f 2e 74 78 74             :path: /hello.txt
```
| Octets | Meaning |
|---|---|
| `02` | Index 2 → `:path` |
| `00 0a` | Value length = 10 |
| `2f 68 …` | `/hello.txt` |

```
001b  04 00 0e 6c 6f 63 61 6c 68 6f 73 74 3a 39 30 30 30 host: localhost:9000
```
| Octets | Meaning |
|---|---|
| `04` | Index 4 → `host` |
| `00 0e` | Value length = 14 |
| `6c 6f …` | `localhost:9000` |

```
002c  07 00 09 62 63 75 72 6c 2f 31 2e 30                user-agent: bcurl/1.0
```
| Octets | Meaning |
|---|---|
| `07` | Index 7 → `user-agent` |
| `00 09` | Value length = 9 |
| `62 63 …` | `bcurl/1.0` |

**Check:** `6 + 13 + 17 + 12 = 48` = the length the header declared. ✓

Every field here used the static table, so every name cost exactly one octet. A name outside
the table would have been `00`, an 8-bit length, and the name itself — see `docs/SPEC.md` §6.

---

## 2. Server → client: `RESPONSE` — 101 octets

```
0000  00 00 5d 02 00 00 00 01  03 00 03 32 30 30 08 00  |..]........200..|
0010  13 6c 69 6e 65 63 61 6c  63 2d 62 73 65 72 76 65  |.linecalc-bserve|
0020  2f 31 2e 30 09 00 1d 4d  6f 6e 2c 20 32 31 20 53  |/1.0...Mon, 21 S|
0030  65 70 20 32 30 32 36 20  31 38 3a 34 39 3a 34 37  |ep 2026 18:49:47|
0040  20 47 4d 54 06 00 19 74  65 78 74 2f 70 6c 61 69  | GMT...text/plai|
0050  6e 3b 20 63 68 61 72 73  65 74 3d 75 74 66 2d 38  |n; charset=utf-8|
0060  05 00 02 31 35                                    |...15|
```

### 2.1 Frame header

| Offset | Octets | Field | Value |
|---|---|---|---|
| `0x00` | `00 00 5d` | Payload Length | `0x5d` = **93** |
| `0x03` | `02` | Type | `0x02` = **`RESPONSE`** |
| `0x04` | `00` | Flags | **No `END_MESSAGE`** — the message is not over, `DATA` follows. Had this been a `HEAD` response the bit would be set here and nothing would follow. |
| `0x05` | `00 00 01` | R + Stream ID | Stream **1** — the id the request arrived on. That is what pairs this response with that request. |

### 2.2 Payload — the header block

| Offset | Octets | Field |
|---|---|---|
| `0x08` | `03 00 03 32 30 30` | index 3 → `:status`, length 3, `200` |
| `0x0e` | `08 00 13` + 19 octets | index 8 → `server`, length `0x13` = 19, `linecalc-bserve/1.0` |
| `0x24` | `09 00 1d` + 29 octets | index 9 → `date`, length `0x1d` = 29, `Mon, 21 Sep 2026 18:49:47 GMT` |
| `0x44` | `06 00 19` + 25 octets | index 6 → `content-type`, length `0x19` = 25, `text/plain; charset=utf-8` |
| `0x61` | `05 00 02 31 35` | index 5 → `content-length`, length 2, `15` |

**Check:** `6 + 22 + 32 + 28 + 5 = 93`. ✓

Note `:status` travels as the three ASCII octets `32 30 30`, not as a 16-bit integer. Encoding
it as a number would save two octets and destroy the one rule that makes the whole block
skimmable: *every* value is a length-prefixed byte string, so a reader can step over a field
without knowing what the field is.

---

## 3. Server → client: `DATA` — 23 octets

```
0000  00 00 0f 03 01 00 00 01  68 65 6c 6c 6f 2c 20 66  |........hello, f|
0010  72 61 6d 69 6e 67 0a                              |raming.|
```

| Offset | Octets | Field | Value |
|---|---|---|---|
| `0x00` | `00 00 0f` | Payload Length | 15 |
| `0x03` | `03` | Type | `0x03` = **`DATA`** |
| `0x04` | `01` | Flags | `0x01` = **`END_MESSAGE`**. *This* is what ends the response. |
| `0x05` | `00 00 01` | R + Stream ID | Stream **1** |
| `0x08` | `68 65 6c …` | Payload | `hello, framing\n` |

The body matches the advertised `content-length` of 15, but the two are not doing the same
job. `END_MESSAGE` terminates the message; `content-length` is advisory, so a client can size
its buffer once instead of growing it. A 10 MB file would arrive as the same `RESPONSE` frame
followed by `DATA` frames of 16 KiB each, with only the last one carrying the flag.

---

## 4. What a version-2 frame would look like here

Suppose a later version adds type `0x07`. A version-1 receiver meeting it reads:

```
0000  00 00 04 07 ff 00 00 01  de ad be ef              |............|
      \_______/ \/ \/ \______/ \_________/
          |     |  |     |          |
          |     |  |     |          +-- 4 octets it cannot interpret
          |     |  |     +------------- stream 1
          |     |  +------------------- flags it does not know: ignored
          |     +---------------------- type 0x07: never heard of it
          +---------------------------- but the length is right there: 4
```

It discards exactly four octets and reads the next header. It does not close, does not error,
does not guess. That single behaviour — guaranteed by the length prefix living at a fixed
offset in *every* frame, understood or not — is what makes a version 2 deployable without
upgrading every peer on the network at the same instant.

`BinaryServerTest.skipsUnknownFrameTypesAndKeepsServing` sends exactly this and then asks for
a real file on the same connection.

---

## 5. What the framing cost

| | HTTP/1.1 | LCB/1 |
|---|---|---|
| Request | 72 octets | 56 octets (+4 preface, once per connection) |
| Response | 146 head + 15 body = 161 octets | 101 + 23 = 124 octets |

Roughly 22% off each way on a request this small, almost entirely from numbering ten header
names. The saving is not the point, though — the point is that the *end of a message* is now a
counted field at a fixed offset rather than a blank line you have to scan for, which is what
made HTTP/1.1 request smuggling a class of bug rather than a mistake.
