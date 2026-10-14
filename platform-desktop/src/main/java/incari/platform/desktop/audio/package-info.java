/**
 * Sound output over OpenAL (LWJGL's OpenAL Soft build). One process-wide context: OpenAL's
 * current context is global, and the game only ever has one device open.
 */
@NullMarked
package incari.platform.desktop.audio;

import org.jspecify.annotations.NullMarked;
