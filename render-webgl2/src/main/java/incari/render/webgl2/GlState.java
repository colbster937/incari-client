package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.BLEND;
import static org.teavm.jso.webgl.WebGL2RenderingContext.CULL_FACE;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DEPTH_TEST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.POLYGON_OFFSET_FILL;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLProgram;

/**
 * WebGL's fixed-function state as last set, so a draw only changes what differs from the one
 * before. Every WebGL call crosses from WebAssembly into JavaScript and is checked by the browser;
 * on a Chromebook that overhead, not the GPU, is what limits the draw rate. Hot path: no
 * allocation.
 */
final class GlState {

    private final WebGL2RenderingContext gl;
    /** Who owns the current program: JavaScript handles don't compare reliably across the boundary. */
    private @Nullable WebGl2Pipeline programOwner;

    private int colorMask = -1;
    private int depthMask = -1;
    private int depthTest = -1;
    private int depthFunc = -1;
    private int cull = -1;
    private int blend = -1;
    private @Nullable BlendFunction blendFunction;
    private float offsetFactor = Float.NaN;
    private float offsetUnits = Float.NaN;
    private final @Nullable JSObject polygonModeExtension;
    private boolean lines;

    GlState(WebGL2RenderingContext gl, @Nullable JSObject polygonModeExtension) {
        this.gl = gl;
        this.polygonModeExtension = polygonModeExtension;
    }

    /** Outlines or filled triangles; only a device with WEBGL_polygon_mode compiles wireframe pipelines. */
    void polygonLines(boolean outline) {
        JSObject extension = polygonModeExtension;
        if (outline != lines && extension != null) {
            WebGl.polygonMode(extension, outline);
            lines = outline;
        }
    }

    void program(WebGl2Pipeline owner, WebGLProgram next) {
        if (owner != programOwner) {
            gl.useProgram(next);
            programOwner = owner;
        }
    }

    /** Forgets the current program; one was deleted or used behind this cache's back. */
    void forgetProgram() {
        programOwner = null;
    }

    void colorMask(int mask) {
        if (mask != colorMask) {
            gl.colorMask((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            colorMask = mask;
        }
    }

    void depthMask(boolean write) {
        int value = write ? 1 : 0;
        if (value != depthMask) {
            gl.depthMask(write);
            depthMask = value;
        }
    }

    /** Depth testing and writing as the pipeline asks; a pipeline without depth state does neither. */
    void depth(@Nullable DepthStencilState state) {
        if (state == null) {
            toggle(DEPTH_TEST, false);
            depthMask(false);
            polygonOffset(0, 0);
            return;
        }
        toggle(DEPTH_TEST, true);
        int function = GlFormats.compareOp(state.depthTest());
        if (function != depthFunc) {
            gl.depthFunc(function);
            depthFunc = function;
        }
        depthMask(state.writeDepth());
        polygonOffset(state.depthBiasScaleFactor(), state.depthBiasConstant());
    }

    void cull(boolean enabled) {
        int value = enabled ? 1 : 0;
        if (value != cull) {
            if (enabled) {
                gl.enable(CULL_FACE);
            } else {
                gl.disable(CULL_FACE);
            }
            cull = value;
        }
    }

    void blend(@Nullable BlendFunction function) {
        if (function == null) {
            if (blend != 0) {
                gl.disable(BLEND);
                blend = 0;
            }
            return;
        }
        if (blend != 1) {
            gl.enable(BLEND);
            blend = 1;
        }
        if (!Objects.equals(function, blendFunction)) {
            gl.blendFuncSeparate(
                    GlFormats.blendFactor(function.color().sourceFactor()),
                    GlFormats.blendFactor(function.color().destFactor()),
                    GlFormats.blendFactor(function.alpha().sourceFactor()),
                    GlFormats.blendFactor(function.alpha().destFactor()));
            gl.blendEquationSeparate(
                    GlFormats.blendOp(function.color().op()),
                    GlFormats.blendOp(function.alpha().op()));
            blendFunction = function;
        }
    }

    private void polygonOffset(float factor, float units) {
        boolean enabled = factor != 0 || units != 0;
        if (factor == offsetFactor && units == offsetUnits) {
            return;
        }
        toggle(POLYGON_OFFSET_FILL, enabled);
        if (enabled) {
            gl.polygonOffset(factor, units);
        }
        offsetFactor = factor;
        offsetUnits = units;
    }

    private void toggle(int capability, boolean enabled) {
        if (capability == DEPTH_TEST) {
            int value = enabled ? 1 : 0;
            if (value == depthTest) {
                return;
            }
            depthTest = value;
        }
        if (enabled) {
            gl.enable(capability);
        } else {
            gl.disable(capability);
        }
    }
}
