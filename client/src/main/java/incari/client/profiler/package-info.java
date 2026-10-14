/**
 * The profiler (ADR 0021): how long each part of a frame takes on the CPU and the GPU, how
 * many draw calls it makes, how deep the mesh queue is and how much memory is in use, shown in an
 * overlay and handed to the platform, where the benchmark runner reads it in the browser.
 */
@NullMarked
package incari.client.profiler;

import org.jspecify.annotations.NullMarked;
