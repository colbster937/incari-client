/*
 *  Copyright 2024 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Modified for Incari (tools/teavm-patches/README.md): the start function, teavm@initializer,
 *  is split where its operand stack is empty into functions of about CHUNK_INSTRUCTIONS
 *  instructions each, which it calls in turn (5 MB of code in the game's build, run once by each
 *  instance; V8 sent it to its optimizing compiler once enough instances had, which took gigabytes
 *  of memory on it: ADR 0042).
 */
package org.teavm.backend.wasm;

import org.teavm.backend.wasm.generate.WasmGCDeclarationsGenerator;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmGlobal;
import org.teavm.backend.wasm.model.instruction.WasmBranch;
import org.teavm.backend.wasm.model.instruction.WasmBreak;
import org.teavm.backend.wasm.model.instruction.WasmInstruction;
import org.teavm.backend.wasm.model.instruction.WasmInstructionList;
import org.teavm.backend.wasm.model.instruction.WasmIntBinaryOperation;
import org.teavm.backend.wasm.model.instruction.WasmIntType;
import org.teavm.backend.wasm.model.instruction.WasmReturn;
import org.teavm.backend.wasm.model.instruction.WasmSwitch;
import org.teavm.backend.wasm.model.instruction.WasmTypeInference;
import org.teavm.backend.wasm.runtime.StringInternPool;
import org.teavm.interop.Address;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.runtime.heap.Heap;

public class WasmGCModuleGenerator {
    private WasmGCDeclarationsGenerator declarationsGenerator;
    private WasmFunction initializer;
    private static final int CHUNK_INSTRUCTIONS = 4000;

    public WasmGCModuleGenerator(WasmGCDeclarationsGenerator declarationsGenerator) {
        this.declarationsGenerator = declarationsGenerator;
    }

    public void generate() {
        declarationsGenerator.generate();
        createInitializer();
    }

    public void initBuffersHeap(WasmGlobal offset, int minSize, WasmGlobal heapLimit) {
        var target = declarationsGenerator.functions().forStaticMethod(new MethodReference(Heap.class,
                "init", Address.class, int.class, int.class, void.class));
        var builder = new WasmInstructionList().builder();
        builder
                .getGlobal(offset)
                .i32Const(minSize)
                .getGlobal(heapLimit)
                .getGlobal(offset)
                .intBinary(WasmIntType.INT32, WasmIntBinaryOperation.SUB)
                .call(target);
        initializer.getBody().transferFrom(builder.list);
    }

    public WasmFunction generateReportGarbageCollectedStringFunction() {
        var entryType = ValueType.object(StringInternPool.class.getName() + "$Entry");
        return declarationsGenerator.functions().forStaticMethod(new MethodReference(
                StringInternPool.class.getName(),
                "remove",
                entryType,
                ValueType.VOID
        ));
    }

    private void createInitializer() {
        var functionType = declarationsGenerator.functionTypes.of(null);
        initializer = new WasmFunction(functionType);
        initializer.setName("teavm@initializer");
        declarationsGenerator.module.functions.add(initializer);
        declarationsGenerator.module.setStartFunction(initializer);
        declarationsGenerator.contributeToInitializer(initializer);
        splitInitializer();
    }

    private void splitInitializer() {
        if (!initializer.getLocalVariables().isEmpty()) {
            return;
        }
        // Where to split: after an instruction that leaves the stack empty, once a chunk is full.
        var inference = new WasmTypeInference();
        var ends = new java.util.HashSet<WasmInstruction>();
        int size = 0;
        int total = 0;
        try {
            for (var insn : initializer.getBody()) {
                if (insn instanceof WasmReturn || insn instanceof WasmBranch || insn instanceof WasmBreak
                        || insn instanceof WasmSwitch) {
                    return;
                }
                insn.acceptVisitor(inference);
                size++;
                total++;
                if (size >= CHUNK_INSTRUCTIONS && inference.typeStack.isEmpty()) {
                    ends.add(insn);
                    size = 0;
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        if (ends.isEmpty() || !inference.typeStack.isEmpty() || total <= CHUNK_INSTRUCTIONS) {
            return;
        }
        var body = initializer.getBody();
        var calls = new java.util.ArrayList<WasmFunction>();
        WasmFunction chunk = null;
        while (body.getFirst() != null) {
            var insn = body.getFirst();
            insn.delete();
            if (chunk == null) {
                chunk = new WasmFunction(declarationsGenerator.functionTypes.of(null));
                chunk.setName("teavm@initializer$" + calls.size());
                declarationsGenerator.module.functions.add(chunk);
                calls.add(chunk);
            }
            chunk.getBody().add(insn);
            if (ends.contains(insn)) {
                chunk = null;
            }
        }
        var builder = body.builder();
        for (var function : calls) {
            builder.call(function);
        }
    }
}
