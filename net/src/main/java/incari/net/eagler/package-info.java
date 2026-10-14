/**
 * The client side of Eagler's protocol (ADR 0016): the V5 handshake that opens every connection to
 * an Eagler server, Eagler's own messages beside the game's packets (skins and capes), and the MOTD
 * query the server list uses. Written from the protocol as EaglerXServer speaks it; no code of
 * EaglerXServer's is used. No game types.
 */
@NullMarked
package incari.net.eagler;

import org.jspecify.annotations.NullMarked;
