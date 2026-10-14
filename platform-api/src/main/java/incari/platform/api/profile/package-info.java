/**
 * Profiling (ADR 0021): what the platform knows about a frame that the game doesn't (memory,
 * garbage collections), and where the game's own per-frame measurements go so tools outside the
 * game can read them (the browser page, for the benchmark runner).
 */
@NullMarked
package incari.platform.api.profile;

import org.jspecify.annotations.NullMarked;
