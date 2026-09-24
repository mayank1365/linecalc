# Convenience targets. Everything here is a one-line wrapper; nothing depends on make.

MVN ?= mvn
PORT_HTTP ?= 8080
PORT_LCB  ?= 9000

.PHONY: all build test clean calc serve curl check

all: build

build:            ## compile and run the test suite
	$(MVN) -q package

test:             ## run the JUnit suite only
	$(MVN) -o test

clean:
	$(MVN) -q clean

calc: build       ## run the persistent HTTP/1.1 calculator
	./httpcalc $(PORT_HTTP)

serve: build      ## run the LCB/1 file server
	./bserve ./www $(PORT_LCB)

curl: build       ## fetch index.html over LCB/1 with hexdumps
	./bcurl -v localhost:$(PORT_LCB)/index.html

check: build      ## the assignment's marking procedure, end to end
	@./httpcalc $(PORT_HTTP) >/dev/null 2>&1 & echo $$! > /tmp/linecalc-http.pid; sleep 1
	@python3 tests/persistent_socket_check.py $(PORT_HTTP); status=$$?; \
	  kill `cat /tmp/linecalc-http.pid` 2>/dev/null; rm -f /tmp/linecalc-http.pid; \
	  exit $$status
