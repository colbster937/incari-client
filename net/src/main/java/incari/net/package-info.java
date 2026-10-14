/**
 * The transport under the game's Netty pipeline: a channel over the platform's connections,
 * WebSocket server addresses, and one-packet-per-message framing (ADR 0009). No game types; later
 * the Eagler handshake and the Via bridge join it as pipeline stages.
 */
@NullMarked
package incari.net;

import org.jspecify.annotations.NullMarked;
