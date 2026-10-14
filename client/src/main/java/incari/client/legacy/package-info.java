/**
 * The legacy behaviour layer (ADR 0020): how the client moves, fights and collides on servers
 * of older versions, 1.8 above all, so it plays as those versions' clients did. Everything keys off
 * the version of the server the player is on ({@link incari.client.legacy.LegacyRules}); in
 * singleplayer and on current servers nothing changes.
 */
@NullMarked
package incari.client.legacy;

import org.jspecify.annotations.NullMarked;
