# tools/wsbridge

- A development tool: never shipped, never a dependency of another module, never exposed beyond
  the loopback interface in examples or scripts.
- It reads only what the login needs (Eagler's handshake, the server's login packets and its
  status); after that, packets are opaque. Anything smarter belongs in a real proxy
  (EaglerXServer, see ADR 0009 and ADR 0016).
- Written from the protocol: never copy EaglerXServer's code.
- Test: `./gradlew :tools:wsbridge:check`.
