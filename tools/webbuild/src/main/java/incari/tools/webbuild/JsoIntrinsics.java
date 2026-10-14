package incari.tools.webbuild;

import org.teavm.ast.InvocationExpr;
import org.teavm.backend.wasm.BaseWasmFunctionRepository;
import org.teavm.backend.wasm.TeaVMWasmGCHost;
import org.teavm.backend.wasm.intrinsics.WasmGCInlineIntrinsic;
import org.teavm.backend.wasm.intrinsics.WasmGCInlineIntrinsicContext;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.instruction.WasmConditional;
import org.teavm.backend.wasm.model.instruction.WasmExternConversionType;
import org.teavm.backend.wasm.model.instruction.WasmInstructionBuilder;
import org.teavm.backend.wasm.model.instruction.WasmIntBinaryOperation;
import org.teavm.backend.wasm.model.instruction.WasmIntType;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * An {@code int} handed to JavaScript without leaving the module. TeaVM's JSO passes each number
 * argument of a JavaScript call through its own import ({@code teavmJso.wrapInt}, which returns
 * its argument), so {@code gl.bindTexture(target, texture)} was two calls out of the module, and a
 * frame in a world made about 3,800 of them. Each costs a crossing, and in the Fast build a dearer
 * one: calls out of a JSPI stack switch stacks (docs/webgpu-backend.md, Measured).
 *
 * <p>An {@code i31ref} turned into an {@code externref} reaches JavaScript as a Number with the same
 * value (WebAssembly's JS API), so an {@code int} in [-2^30, 2^30) goes as one; WebGL's enums,
 * sizes and offsets all fit. Others still take the import.
 *
 * <p>Registered in {@code META-INF/services}, as {@link MathIntrinsics} is. The system property
 * {@code incari.jsoIntrinsics=false} ({@code -Pteavm.noJsoIntrinsics}) leaves it out, to compare.
 */
public final class JsoIntrinsics implements TeaVMPlugin {

    private static final MethodReference WRAP_INT = new MethodReference(
            "org.teavm.jso.impl.JS", "wrap", ValueType.INTEGER, ValueType.object("org.teavm.jso.JSObject"));

    @Override
    public void install(TeaVMHost host) {
        TeaVMWasmGCHost wasm = host.getExtension(TeaVMWasmGCHost.class);
        if (wasm == null || !Boolean.parseBoolean(System.getProperty("incari.jsoIntrinsics", "true"))) {
            return;
        }
        wasm.contributeToCodeGen((context, registry) ->
                registry.inlineIntrinsics().registerIntrinsic(WRAP_INT, new WrapInt(context.functions())));
    }

    /** {@code JS.wrap(int)}: an i31 when the value fits, else the import. */
    private static final class WrapInt implements WasmGCInlineIntrinsic {

        private final BaseWasmFunctionRepository functions;

        WrapInt(BaseWasmFunctionRepository functions) {
            this.functions = functions;
        }

        @Override
        public void apply(
                InvocationExpr invocation, WasmGCInlineIntrinsicContext context, WasmInstructionBuilder builder) {
            WasmLocal value = context.tempVars().acquire(WasmType.INT32);
            context.generate(builder, invocation.getArguments().get(0));
            builder.teeLocal(value);
            // value + 2^30 < 2^31 (unsigned) exactly when value is in [-2^30, 2^30).
            builder.i32Const(1 << 30);
            builder.intBinary(WasmIntType.INT32, WasmIntBinaryOperation.ADD);
            builder.i32Const(Integer.MIN_VALUE);
            builder.intBinary(WasmIntType.INT32, WasmIntBinaryOperation.LT_UNSIGNED);
            WasmConditional fits = builder.conditional(WasmType.EXTERN);
            new WasmInstructionBuilder(fits.getThenBlock())
                    .getLocal(value)
                    .i31Ref()
                    .externConvert(WasmExternConversionType.ANY_TO_EXTERN);
            new WasmInstructionBuilder(fits.getElseBlock()).getLocal(value).call(functions.forStaticMethod(WRAP_INT));
            context.tempVars().release(value);
        }
    }
}
