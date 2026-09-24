# LCB/1 — the linecalc binary protocol

**Version 1 · Mayank Gupta (23BCS10069)**

A request/response protocol with a binary framing layer, over one long-lived TCP connection.
This document is the contract: an implementation written from it alone must interoperate.

MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119. Integers are unsigned and big-endian.
"Octet" means 8 bits. Rationale for the choices below is in `docs/design-notes.md`; the
conformance checklist is in `docs/conformance.md`.

## 1. Connection

The client sends a 4-octet preface before its first frame:

```
4c 43 42 31        "LCB1"
```

A server MUST read these four octets first and MUST close without replying if they differ.
The trailing `1` is the version, so `LCB2` is distinguishable from the first octet onward.

The connection is persistent. A client SHOULD reuse it for every request to the same host and
port, and MUST NOT open a second connection merely because it has a second request. Either
side may close; both MUST tolerate a close on any frame boundary.

## 2. Frame layout

Every frame is an 8-octet header followed by exactly `Length` octets of payload.

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

| Field | Width | Rule |
|---|---|---|
| Payload Length | 24 bits | Octets following the header. Excludes the header itself. |
| Type | 8 bits | §3. An unknown value MUST be skipped, not rejected (§4). |
| Flags | 8 bits | §3. Undefined bits MUST be sent as 0 and MUST be ignored on receipt. |
| R | 1 bit | Reserved. MUST be sent as 0 and MUST be ignored on receipt. |
| Stream ID | 23 bits | §5. Mask off R before use. |

**Size limit.** A receiver MUST reject a frame whose `Length` exceeds **65,536**. It MUST do so
by discarding exactly `Length` octets — the length prefix is still trustworthy enough to
resynchronise with — and replying `400`. It MUST NOT close. A sender MUST split a larger body
across multiple `DATA` frames.

## 3. Frame types

| Code | Name | Direction | Payload |
|---|---|---|---|
| `0x01` | `REQUEST` | client → server | Header block (§6) |
| `0x02` | `RESPONSE` | server → client | Header block (§6) |
| `0x03` | `DATA` | server → client | Raw body octets |
| `0x04` | `PING` | either | 0–8 opaque octets |

| Bit | Name | Applies to | Meaning |
|---|---|---|---|
| `0x01` | `END_MESSAGE` | `REQUEST`, `RESPONSE`, `DATA` | Last frame of this message. |
| `0x01` | `ACK` | `PING` | This frame is an echo, not a new probe. |

`END_MESSAGE` and `ACK` share a bit because they apply to disjoint frame types.

- **`REQUEST`** MUST set `END_MESSAGE` (version 1 has no request bodies) and MUST carry
  `:method`, `:path` and `host`. Its stream id MUST be odd and non-zero.
- **`RESPONSE`** MUST carry `:status`. It sets `END_MESSAGE` only when no `DATA` follows — a
  `HEAD` response, for instance.
- **`DATA`** carries body octets on the stream id of the request it answers. The final one MUST
  set `END_MESSAGE`. An empty body is one zero-length `DATA` with the flag set.
- **`PING`** MUST use stream id 0 and MUST carry at most 8 octets. A receiver seeing a `PING`
  without `ACK` MUST reply with the identical payload and `ACK` set. A `PING` with `ACK` set
  MUST NOT be answered.

## 4. Unknown frames

> **A receiver that encounters a frame type it does not understand MUST discard exactly
> `Length` octets of payload and continue reading. It MUST NOT close the connection, MUST NOT
> reply with an error, and MUST NOT attempt to interpret the payload.**

Because the length prefix is universal and sits at a fixed offset, a receiver can always
determine a frame's size without understanding its meaning. An implementation MAY log that it
skipped something; it MUST NOT let that change the octets it reads.

## 5. Streams

Every request/response exchange happens on one stream id.

- Client-initiated ids are **odd**, start at 1, and strictly increase. Even ids are reserved
  for server-initiated exchanges, which version 1 does not use.
- Id `0` addresses the connection itself: `PING` uses it, nothing else may.
- An id is **never reused** on a connection.
- A server MUST answer on the id it received. A `REQUEST` on an even id, on id 0, or on an id
  not greater than one already seen MUST be answered `400`.

Version 1 is not multiplexed: a client sends one request and reads its response before sending
the next.

## 6. Header blocks

The payload of a `REQUEST` or `RESPONSE` is a sequence of header fields packed back to back.
There is no field count — the block runs to the end of the payload, whose length the frame
header already gave. Each field:

```
+---------------+
|  Name code (8)|   1..10 = static table index,  0 = a literal name follows
+---------------+
| Name len (8)  |   \  present only when Name code == 0
| Name (len)    |   /
+---------------+
| Value len (16)|   always present
| Value (len)   |
+---------------+
```

Names MUST be lowercase and MUST be valid HTTP tokens. Values are opaque UTF-8, at most 65,535
octets, and are **always** length-prefixed — including `:status`, which travels as the ASCII
string `"200"`, not as an integer.

### 6.1 Static table

| # | Name | | # | Name |
|---|---|---|---|---|
| 1 | `:method` | | 6 | `content-type` |
| 2 | `:path` | | 7 | `user-agent` |
| 3 | `:status` | | 8 | `server` |
| 4 | `host` | | 9 | `date` |
| 5 | `content-length` | | 10 | `connection` |

Indices are on the wire, so this table is frozen; a later version may only append. A name
outside the table travels as a literal. Receiving an index above 10 MUST be answered `400`.

### 6.2 Pseudo-headers

`:method`, `:path` and `:status` begin with a colon, which is not legal in an HTTP token, so
the control namespace is disjoint from real header names. Requests use `:method` and `:path`;
responses use `:status`. Neither may use the other's.

## 7. Request and response semantics

A server MUST support `GET` and SHOULD support `HEAD`; any other method is `405`.

Paths resolve against a document root given at startup. A server MUST reject a path that
escapes the root after normalisation, MUST reject a path not starting with `/`, and SHOULD map
a trailing `/` to `index.html`.

| Status | When |
|---|---|
| `200` | Found; the body follows. |
| `400` | Malformed frame or header block, or an invalid stream id. |
| `403` | The path resolved outside the document root. |
| `404` | No such file. |
| `405` | The method is not `GET` or `HEAD`. |
| `500` | The server failed reading a file it had already found. |

A response carries `:status`, and for a body also `content-type` and `content-length`.
`content-length` MUST equal the total octets across the following `DATA` frames; it is
advisory, since `END_MESSAGE` is what terminates the body.

### 7.1 Error recovery

A receiver MUST distinguish two failures:

- **Framing intact** — the frame header was read, so `Length` octets were consumed and the next
  header is where it should be. Reply `400` and **keep the connection open**. Malformed header
  blocks, bad stream ids and oversize frames land here.
- **Framing lost** — a header or payload was truncated. The receiver MAY reply `400` and then
  MUST close, having no way to locate the next frame boundary.

---

A complete annotated exchange is in `docs/annotated-frame.md`.
