# linecalc

Two exercises in message framing over one TCP connection:

1. **A persistent HTTP/1.1 calculator** — one socket, many requests, framed by `Content-Length`.
2. **A custom binary HTTP-like protocol** — `bserve` (server) and `bcurl` (client) over a
   fixed-size 8-byte frame header.

No web framework. Raw sockets only. Java 17, built with Maven.

Usage and the full wire format land in `docs/SPEC.md`.
