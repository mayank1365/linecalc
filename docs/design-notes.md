# Design notes — defending the choices

> *"A fixed-size frame header — you pick the fields and the widths, and you defend them.
> HTTP/2 chose 24 / 8 / 8 / 31. Why?"*

`docs/SPEC.md` says what LCB/1 is. This says why, and is kept separate so the spec itself stays
to two pages.

## Why the header is eight octets

HTTP/2 chose 24 / 8 / 8 / 1+31, which totals **nine**. Nine is the one size in that
neighbourhood with no redeeming property: every header after the first straddles an 8-octet
boundary, so a parser can never load one as an aligned machine word, and a struct mapped over
it needs explicit packing.

LCB/1 keeps HTTP/2's first three fields unchanged and spends **23** bits on the stream id
instead of 31. That yields a header of exactly eight octets — one aligned read — and the only
thing given up is stream ids above 8,388,607.

### Field by field

- **Length, 24 bits.** 16 bits caps a frame at 64 KiB, forcing a 10 MB response into 160-odd
  frames and a header parse per 64 KiB. 32 bits lets a peer announce a 4 GiB allocation in the
  first four octets it ever sends. 24 bits is the same compromise HTTP/2 reached.
- **Type, 8 bits.** Version 1 uses four codes; the remaining 252 are the extension budget, and
  the skip rule is what makes that budget spendable.
- **Flags, 8 bits.** One is defined. A flag is a per-frame boolean that would otherwise cost a
  payload octet plus a parse step, so keeping the common case (`END_MESSAGE`) out of the
  payload entirely is free.
- **R, 1 bit.** Reserved *and specified as ignored*. This matters more than it looks: a bit
  receivers are required to ignore is a bit a later version can actually use, because no
  deployed peer rejects a frame for setting it. A bit that version 1 validated strictly would
  be permanently unusable. The same reasoning covers undefined flag bits.
- **Stream ID, 23 bits.** 8,388,607 request slots per connection. Ids are never reused, so this
  is a budget rather than a ceiling on concurrency. A client issuing 1,000 requests per second
  exhausts it in about 2h20m and then opens a fresh connection — an acceptable price for the
  aligned header, given that version 1 is not multiplexed at all.

## Why stream ids exist before multiplexing does

Version 1 sends one request at a time, so the field does nothing yet. It is on the wire because
retrofitting request/response correlation onto a protocol that assumed strict ordering is
precisely the mistake HTTP/1.1 pipelining made, and it cost the web fifteen years of
head-of-line blocking. Adding interleaving later is then an implementation change, not a format
change.

## Why limits are policy, not structure

The 24-bit length field permits 16 MiB; version 1 accepts 64 KiB. The structural maximum is
what the format can express; the policy maximum is what we are willing to allocate on the
strength of three octets from a stranger. They should not be the same number.

An oversize frame is *skipped* rather than fatal, for the same reason an unknown type is: the
length prefix is still good enough to resynchronise with, so the connection survives.

## Why the header block works this way

**Ten numbered names** is HPACK's first mechanism and nothing more. Real traffic reuses a tiny
vocabulary of header names endlessly, so spelling out `content-length` as fifteen octets on
every response is pure waste; an index turns it into one. Anything outside the table travels as
a literal, so the table being short costs bytes and never correctness.

**No field count.** The block runs to the end of the payload, whose length the frame header
already stated. A count would be a second source of truth about one fact, and two sources of
truth can disagree — which is exactly the shape of HTTP/1.1 request smuggling.

**`:status` as ASCII.** Encoding it as a 16-bit integer would save two octets and destroy the
one rule that makes a block skimmable: *every* value is a length-prefixed byte string, so a
reader can step over a field without knowing what the field is. A reader that must identify a
field before it can compute that field's width cannot skip a field it does not recognise.

**An unknown static index is `400`, not skipped.** Unlike an unknown frame type, the damage is
not bounded: we would be silently dropping a header whose meaning could be load-bearing. A
later version may only append to the table, so seeing a high index at all means the peer is out
of spec.

**No dynamic table.** HPACK's third mechanism — entries added at runtime and referenced by
later requests — is where the real compression ratio lives, and it is deliberately absent. It
requires both peers to evolve byte-identical tables in lockstep, turns each header block into a
stateful delta against every block before it, and is what made the CRIME class of attacks
possible. Version 1 stays stateless.

## Why the preface is worth four octets

Four octets, once per connection, turn an unbounded class of confusing parse failures into one
unambiguous one: a peer speaking some other protocol is detected before either side has
committed to an interpretation of anything. Replying to it would only add noise, so the server
hangs up.

## Why framing failures are split in two

Both `HttpException` and `ProtocolException` carry a `framingIntact()` flag, and every error
path consults it:

- Stream still aligned → answer and keep serving.
- Boundary lost → answer if possible, then close.

Collapsing these into "it's a 400" either closes connections that were fine, or keeps reading a
stream that is now garbage being parsed as structure. The second is worse, and is how a parser
turns a malformed request into a security bug.

## The same argument, in the HTTP/1.1 half

Track 1 is this protocol's problem in its original form. HTTP/1.0 could answer "where does this
message end?" with "at EOF" — the close told you, for free. Keeping the connection open takes
that away, so the boundary must be derived: scan for a blank line, then trust a
`Content-Length` header that may be absent, duplicated, or contradicted by
`Transfer-Encoding`.

LCB/1's answer is to make the length a counted field at a fixed offset that cannot be missing,
misspelled, or duplicated. That single difference is why HTTP/1.1 has a request-smuggling
literature and HTTP/2 does not — and it is why `Content-Length` and `Transfer-Encoding`
arriving together is a flat `400` in Track 1 rather than an ambiguity to be resolved by
preferring one.
