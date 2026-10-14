package incari.tools.webbuild;

import org.teavm.ast.InvocationExpr;
import org.teavm.backend.wasm.TeaVMWasmGCHost;
import org.teavm.backend.wasm.intrinsics.WasmGCInlineIntrinsic;
import org.teavm.backend.wasm.intrinsics.WasmGCInlineIntrinsicContext;
import org.teavm.backend.wasm.model.instruction.WasmFloatType;
import org.teavm.backend.wasm.model.instruction.WasmFloatUnaryOperation;
import org.teavm.backend.wasm.model.instruction.WasmInstructionBuilder;
import org.teavm.model.MethodReference;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * {@code Math.floor}, {@code Math.ceil} and {@code Math.sqrt} as WebAssembly's own {@code f64.floor},
 * {@code f64.ceil} and {@code f64.sqrt}. TeaVM's class library imports them from JavaScript's
 * {@code Math}, so each was a call out of the module and back: world generation's noise calls
 * {@code floor} for every sample, and those calls were 8 to 18% of the server worker's busy time
 * (the singleplayer benchmark's profiles). All three are exact in IEEE 754, which Java and
 * WebAssembly both follow, so the results are the same; only a NaN's payload may differ, which no
 * Java code can tell apart from {@code Math}'s results.
 *
 * <p>Registered in {@code META-INF/services}; TeaVM loads it from the compile's class loader. Its
 * call-site intrinsics come before any call, so the imports go unused.
 */
public final class MathIntrinsics implements TeaVMPlugin {

    @Override
    public void install(TeaVMHost host) {
        TeaVMWasmGCHost wasm = host.getExtension(TeaVMWasmGCHost.class);
        if (wasm == null) {
            return;
        }
        wasm.contributeToCodeGen((context, registry) -> {
            for (String name : new String[] {"floor", "ceil", "sqrt"}) {
                registry.inlineIntrinsics()
                        .registerIntrinsic(
                                new MethodReference(Math.class, name, double.class, double.class),
                                new Unary(operation(name)));
            }
        });
    }

    private static WasmFloatUnaryOperation operation(String name) {
        return switch (name) {
            case "floor" -> WasmFloatUnaryOperation.FLOOR;
            case "ceil" -> WasmFloatUnaryOperation.CEIL;
            case "sqrt" -> WasmFloatUnaryOperation.SQRT;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** One {@code double -> double} instruction in place of the call. */
    private record Unary(WasmFloatUnaryOperation operation) implements WasmGCInlineIntrinsic {
        @Override
        public void apply(
                InvocationExpr invocation, WasmGCInlineIntrinsicContext context, WasmInstructionBuilder builder) {
            context.generate(builder, invocation.getArguments().get(0));
            builder.floatUnary(WasmFloatType.FLOAT64, operation);
        }
    }
}
