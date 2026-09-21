# LCB/1 — the linecalc binary protocol

**Version 1 · Mayank Gupta (23BCS10069)**

An HTTP-shaped request/response protocol with a binary framing layer, carried over one
long-lived TCP connection. A stranger holding this document should be able to write an
interoperating peer without reading our source.

Conventions: MUST, MUST NOT, SHOULD and MAY are used in the RFC 2119 sense. All integers are
unsigned and big-endian (network byte order). "Octet" means 8 bits.

---

## 1. Connection

A client opens one TCP connection and sends the 4-octet **preface** before anything else:

```
4c 43 42 31        "LCB1"
```

A server MUST read these four octets first. If they differ it MUST close the connection
without replying: the peer is speaking some other protocol, and a reply would be noise at
best. The preface costs four octets once per connection and turns an unbounded class of
confusing parse failures into one unambiguous one. The trailing `1` is the protocol version;
a future `LCB2` is distinguishable from the very first octets, before either side has
committed to an interpretation of anything.

After the preface both peers exchange frames until one closes. The connection is persistent by
default. A client SHOULD reuse it for every request to the same host and port, and MUST NOT
open a second connection merely because it has a second request.

---

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
+---------------------------------------------------------------+
```

| Field | Width | Notes |
|---|---|---|
| Payload Length | 24 bits | Octets of payload that follow the header. Excludes the header. |
| Type | 8 bits | See §3. Unknown values MUST be skipped, not rejected. |
| Flags | 8 bits | Per-type booleans. Undefined flags MUST be sent as 0 and ignored on receipt. |
| R | 1 bit | Reserved. MUST be sent as 0 and MUST be ignored on receipt. |
| Stream ID | 23 bits | Pairs a response with its request. See §5. |

### 2.1 Why these widths

HTTP/2 chose 24 / 8 / 8 / 1+31, which totals nine octets. Nine is the one number in that
neighbourhood with no redeeming property: every header after the first straddles an 8-octet
boundary, so a parser can never load one as an aligned machine word and a `struct` mapped over
it needs explicit packing. We keep HTTP/2's first three fields unchanged and spend 23 bits on
the stream id instead of 31. That yields **exactly eight octets** — one aligned read, one
cache-friendly struct — and the only thing given up is stream ids above 8,388,607.

- **Length at 24 bits.** 16 bits would cap a frame at 64 KiB, forcing a 10 MB response into
  160-odd frames and a header-parse per 64 KiB. 32 bits would let a peer announce a 4 GiB
  allocation in the first four octets it ever sends. 24 bits (16 MiB) is the same compromise
  HTTP/2 reached, and we then apply a *policy* cap far below the structural one (§2.2).
- **Type at 8 bits.** Version 1 uses four codes. The remaining 252 are the extension budget,
  and §4 is what makes that budget spendable.
- **Flags at 8 bits.** One is defined. A flag is a per-frame boolean that would otherwise cost
  a payload octet plus a parse step; having a byte of them costs nothing and keeps the
  common case (`END_MESSAGE`) out of the payload entirely.
- **R at 1 bit.** Reserved *and defined as ignored*. This matters more than it looks: a bit
  that receivers are required to ignore is a bit a later version can actually use, because no
  deployed peer rejects a frame for setting it. A bit that version 1 validated strictly would
  be permanently unusable.
- **Stream ID at 23 bits.** 8,388,607 request slots per connection. Ids are never reused
  (§5), so this is a budget, not a ceiling on concurrency. A client issuing 1,000 requests per
  second exhausts it in about 2h20m and then opens a fresh connection — an acceptable price
  for the aligned header, given that version 1 is not even multiplexed.

### 2.2 Size limits

Structurally a peer may announce 16 MiB. Trusting that on the strength of three octets from a
stranger means allocating 16 MiB per connection on demand, so:

- A receiver MUST reject a frame whose `Length` exceeds **65,536**. It MUST do so by
  discarding exactly `Length` octets (the length prefix is still trustworthy enough to
  resynchronise with) and replying `400`, not by closing.
- A sender MUST split a body larger than that across multiple `DATA` frames. The reference
  implementation uses 16 KiB.

---

## 3. Frame types

| Code | Name | Direction | Payload |
|---|---|---|---|
| `0x01` | `REQUEST` | client → server | Header block (§6) |
| `0x02` | `RESPONSE` | server → client | Header block (§6) |
| `0x03` | `DATA` | server → client | Raw body octets |
| `0x04` | `PING` | either | 0–8 opaque octets |

### Flags

| Bit | Name | Applies to | Meaning |
|---|---|---|---|
| `0x01` | `END_MESSAGE` | `REQUEST`, `RESPONSE`, `DATA` | Last frame of this message. |
| `0x01` | `ACK` | `PING` | This frame is an echo, not a new probe. |

`END_MESSAGE` and `ACK` share a bit because they apply to disjoint frame types, so no frame is
ever ambiguous. Flag bits are a scarce, non-renewable resource — there are eight of them and
they are in every single frame — and spending two on what one can express would be waste.

### Per-type rules

- **`REQUEST`** MUST set `END_MESSAGE`; version 1 has no request bodies. It MUST carry
  `:method`, `:path` and `host`. Its stream id MUST be odd and non-zero.
- **`RESPONSE`** MUST carry `:status`. It sets `END_MESSAGE` only when no `DATA` follows
  (a `HEAD` response, for instance).
- **`DATA`** carries body octets on the stream id of the request it answers. The final one
  MUST set `END_MESSAGE`; an empty body is one zero-length `DATA` with the flag set.
- **`PING`** MUST use stream id 0 and MUST carry at most 8 octets. A receiver seeing a `PING`
  without `ACK` MUST reply with the identical payload and `ACK` set. A `PING` with `ACK` set
  MUST NOT be answered, or two peers would ping each other forever.

---

## 4. Unknown frames — the rule you may not skip

> **A receiver that encounters a frame type it does not understand MUST discard exactly
> `Length` octets of payload and continue reading. It MUST NOT close the connection, MUST NOT
> reply with an error, and MUST NOT attempt to interpret the payload.**

This single sentence is the difference between a protocol and a format. Because the length
prefix is universal and sits in a fixed position in every frame, a receiver can always
determine a frame's size without understanding its meaning — so a version-2 peer can send
version-2 frames to a version-1 peer and the version-1 peer will step over them correctly
instead of dying. Without this rule, every extension is a flag day on which every deployed
peer must be upgraded simultaneously.

The same reasoning is why undefined flag bits and the `R` bit are defined as *ignored* rather
than *invalid*: strict validation of a field nobody uses yet is a decision to never use it.

An implementation MAY log that it skipped something. It MUST NOT let that change the bytes.

---

## 5. Streams

Every request/response exchange happens on one stream id.

- Client-initiated ids are **odd**, starting at 1, and strictly increase. Even ids are
  reserved for server-initiated exchanges, which version 1 does not have.
- Id `0` is the connection itself, not a request: `PING` uses it, and nothing else may.
- An id is **never reused** on a connection. Reuse invites a response to a cancelled request
  being matched to its successor.
- A server MUST answer on the id it received. A server receiving a `REQUEST` on an even id, on
  id 0, or on an id not greater than one it has already seen MUST reply `400`.

Version 1 is not multiplexed: a client sends one request and reads its response before
sending the next. The field exists now because retrofitting request/response correlation onto
a protocol that assumed strict ordering is exactly the mistake HTTP/1.1 pipelining made, and
it cost the web fifteen years of head-of-line blocking.

---

## 6. Header blocks

The payload of a `REQUEST` or `RESPONSE` is a sequence of header fields packed back to back.
There is no field count: the block runs to the end of the payload, whose length the frame
header already stated. A count would be a second source of truth about one fact, and two
sources of truth can disagree.

Each field:

```
+---------------+
|  Name code (8)|   1..10 = static table index (§6.1)
|               |   0     = a literal name follows
+---------------+
| Name len (8)  |   \  present only when Name code == 0
| Name (len)    |   /
+---------------+
| Value len (16)|   always present
| Value (len)   |
+---------------+
```

Names MUST be lowercase and MUST be valid HTTP tokens. Values are opaque UTF-8 octets, at most
65,535 of them. Values are *always* length-prefixed, including `:status`, which travels as the
ASCII string `"200"` and not as a 16-bit integer: special-casing it would save two octets and
cost the uniform rule that any reader can find the end of any field without understanding it.

### 6.1 Static table

Ten names, numbered. This is HPACK's first mechanism. Real traffic reuses a tiny vocabulary of
names endlessly, so spending fifteen octets to spell `content-length` on every response is
pure waste; an index turns it into one.

| # | Name | | # | Name |
|---|---|---|---|---|
| 1 | `:method` | | 6 | `content-type` |
| 2 | `:path` | | 7 | `user-agent` |
| 3 | `:status` | | 8 | `server` |
| 4 | `host` | | 9 | `date` |
| 5 | `content-length` | | 10 | `connection` |

Indices are on the wire, so this table is **frozen**. A version 2 may only append; reordering
or inserting would silently change the meaning of octets already in flight. Any name outside
the table travels as a literal, so the table's shortness costs bytes and never correctness.
Receiving an index above 10 is a `400` — a peer using it is out of spec, and unlike an unknown
frame type, silently dropping a header whose name we cannot resolve could discard something
load-bearing.

### 6.2 Pseudo-headers

`:method`, `:path` and `:status` begin with a colon, which is not legal in an HTTP token. That
is the point: it makes the namespace of control fields provably disjoint from the namespace of
real header names, so no client can forge a `:status` by naming a header cleverly. Requests
use `:method` and `:path`; responses use `:status`. Neither may use the other's.

### 6.3 What this leaves out

HPACK's third mechanism, the **dynamic table** — entries added at runtime and referenced by
later requests — is where the real compression ratio lives, and it is deliberately absent.
It requires both peers to evolve byte-identical tables in lockstep, turns each header block
into a stateful delta against every block before it, and is what made the CRIME class of
attacks possible. A version 2 may add it; version 1 stays stateless.

---

## 7. Request and response semantics

A request names a method and a path. Version 1 servers MUST support `GET` and SHOULD support
`HEAD`; any other method is `405`.

Paths are resolved against a document root supplied at startup. A server MUST reject any path
that escapes the root after normalisation, MUST reject paths not starting with `/`, and
SHOULD map a trailing `/` to `index.html`.

| Status | When |
|---|---|
| `200` | The file was found and is being sent. |
| `400` | The frame or header block was malformed, or the stream id was invalid. |
| `403` | The path resolved outside the document root. |
| `404` | No such file. |
| `405` | The method is not `GET` or `HEAD`. |
| `500` | The server failed while reading a file it had already found. |

A response carries `:status`, `content-length`, and `content-type` for a body. `content-length`
MUST equal the total octets across the following `DATA` frames — it is advisory here, since
`END_MESSAGE` is what actually terminates the body, but a client that can size its buffer in
advance does not have to grow one.

### 7.1 Error recovery

A server MUST distinguish two failures:

- **Framing intact** — the frame header was read, so `Length` octets were consumed and the
  next header is where it should be. The server replies `400` and **keeps the connection
  open**. Malformed header blocks and bad stream ids land here.
- **Framing lost** — a header or payload was truncated. The server MAY reply `400` and then
  MUST close, because it can no longer locate the next frame boundary and every subsequent
  read would be garbage interpreted as structure.

---

## 8. Worked example

`docs/annotated-frame.md` contains a complete request and response captured from the reference
implementation, every octet annotated.
