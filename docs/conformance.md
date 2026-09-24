# LCB/1 conformance

Two ways to check an implementation against `docs/SPEC.md`: run the tester, or work the
checklist by hand.

## The tester

`tests/conformance_check.py` speaks LCB/1 from the spec alone. It shares no code with the Java
implementation — it builds every octet it sends with `struct.pack` straight from §2 and §6 —
so passing it means a peer matches the *document*, not this repository's habits.

```bash
./bserve ./www 9000 &
python3 tests/conformance_check.py localhost 9000
```

It takes an optional host, port and `--path` (a file that exists in the target's document
root), and exits non-zero if any check fails. Point it at any LCB/1 server, not just this one:

```bash
python3 tests/conformance_check.py 10.0.0.5 9000 --path /home.html
```

Current result against `bserve`: **18/18**.

### If you are the other half of a pair

Run this against your server before we exchange anything else. Every check names the section it
comes from, so a failure tells you which paragraph we read differently — which is the only
interesting output either of us can produce. A client that only works against its author's
server is an implementation, not a protocol.

## The checklist

For review by hand, or for parts the tester does not reach.

### Framing

- [ ] The client sends `4c 43 42 31` before its first frame; the server closes without reply on
      anything else.
- [ ] Every frame header is read as exactly 8 octets, every payload as exactly `Length`.
- [ ] A frame with `Length` above 65,536 is skipped — all `Length` octets discarded — and
      answered `400`, **not** closed.
- [ ] A truncated header or payload closes the connection rather than being answered and
      resumed.

### Extensibility (§4)

- [ ] An unknown frame **type** is skipped cleanly and the connection continues.
- [ ] An undefined **flag** bit is ignored, not rejected.
- [ ] The **R** bit is ignored on receipt and sent as 0.

### Streams (§5)

- [ ] Client stream ids are odd, non-zero and strictly increasing; violations are `400`.
- [ ] `PING` uses stream id 0; nothing else does.
- [ ] Responses are emitted on the stream id of their request.

### Header blocks (§6)

- [ ] The block is parsed until the payload is exhausted, with no field count consulted.
- [ ] Static indices 1–10 decode to §6.1's names in that order; index 0 reads a literal name;
      an index above 10 is `400`.
- [ ] Values are read as exactly their 16-bit length, including `:status`.
- [ ] Names are lowercase; uppercase or non-token octets are `400`.

### Semantics (§7)

- [ ] `REQUEST` carries `:method`, `:path` and `host`, and sets `END_MESSAGE`.
- [ ] `RESPONSE` carries `:status`.
- [ ] The last `DATA` frame sets `END_MESSAGE`; an empty body is one zero-length `DATA` with it.
- [ ] A `HEAD` response sets `END_MESSAGE` on the `RESPONSE` frame and sends no `DATA`.
- [ ] A `PING` without `ACK` is echoed with `ACK` and the same payload; a `PING` with `ACK` is
      not answered.
- [ ] Paths escaping the document root are `403`; missing files are `404`; methods other than
      `GET`/`HEAD` are `405`.
- [ ] The connection stays open across all of the above except a lost frame boundary.
