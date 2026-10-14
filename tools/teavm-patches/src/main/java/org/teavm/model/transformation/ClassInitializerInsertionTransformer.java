/*
 *  Copyright 2017 Alexey Andreev.
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
 *  Modified for Incari (tools/teavm-patches/README.md): a class's static initializer now first
 *  initializes its superclass (the nearest one with a dynamic initializer), as the JLS requires
 *  (12.4.2, step 7). TeaVM only initialized a class at the entry of its own constructors and static
 *  members, so "new Sub()" ran Sub's static initializer before its superclass's.
 */
package org.teavm.model.transformation;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;
import org.teavm.model.Program;
import org.teavm.model.analysis.ClassInitializerInfo;
import org.teavm.model.instructions.InitClassInstruction;

public class ClassInitializerInsertionTransformer {
    private static final MethodDescriptor clinitDescriptor = new MethodDescriptor("<clinit>", void.class);
    private ClassReaderSource classes;
    private ClassInitializerInfo classInitializerInfo;

    public ClassInitializerInsertionTransformer(ClassReaderSource classes, ClassInitializerInfo classInitializerInfo) {
        this.classes = classes;
        this.classInitializerInfo = classInitializerInfo;
    }

    public void apply(MethodReader method, Program program) {
        if (program.basicBlockCount() == 0) {
            return;
        }
        ClassReader cls = classes.get(method.getOwnerName());
        if (method.getName().equals("<clinit>") && method.hasModifier(ElementModifier.STATIC)
                && !cls.hasModifier(ElementModifier.INTERFACE)) {
            // Incari: the superclass first. Its own initializer does the same for its superclass.
            String superclass = nearestDynamicInitializer(cls.getParent());
            if (superclass != null) {
                InitClassInstruction initInsn = new InitClassInstruction();
                initInsn.setClassName(superclass);
                program.basicBlockAt(0).addFirst(initInsn);
            }
        }
        boolean hasClinit = cls.getMethod(clinitDescriptor) != null
                && classInitializerInfo.isDynamicInitializer(cls.getName());
        if (hasClinit && needsClinitCall(method)) {
            BasicBlock entryBlock = program.basicBlockAt(0);
            InitClassInstruction initInsn = new InitClassInstruction();
            initInsn.setClassName(method.getOwnerName());
            entryBlock.addFirst(initInsn);
        }
    }

    // Incari: the nearest class from className up whose static initializer runs at run time.
    private String nearestDynamicInitializer(String className) {
        while (className != null) {
            ClassReader cls = classes.get(className);
            if (cls == null) {
                return null;
            }
            if (cls.getMethod(clinitDescriptor) != null && classInitializerInfo.isDynamicInitializer(className)) {
                return className;
            }
            className = cls.getParent();
        }
        return null;
    }

    private static boolean needsClinitCall(MethodReader method) {
        if (method.getName().equals("<clinit>")) {
            return false;
        }
        if (method.getName().equals("<init>")) {
            return true;
        }
        return method.hasModifier(ElementModifier.STATIC);
    }
}
