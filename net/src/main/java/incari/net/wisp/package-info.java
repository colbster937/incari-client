/**
 * Wisp (Mercury Workshop's TCP-over-WebSocket protocol, as Wispcraft uses it): a page joins a Java
 * server's TCP port through a Wisp proxy, and the game's own TCP pipeline (framing, encryption,
 * compression) runs on top unchanged. Written from the protocol's spec. No game types.
 */
@NullMarked
package incari.net.wisp;

import org.jspecify.annotations.NullMarked;
