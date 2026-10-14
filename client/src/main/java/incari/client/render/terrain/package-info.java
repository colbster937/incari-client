/**
 * Terrain's draw list (ADR 0030): each section's draws kept between frames, the frame's indirect
 * commands written without an object per section, and quads facing away from the camera left out.
 */
@NullMarked
package incari.client.render.terrain;

import org.jspecify.annotations.NullMarked;
