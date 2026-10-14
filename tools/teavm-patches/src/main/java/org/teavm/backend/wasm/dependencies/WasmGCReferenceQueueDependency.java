/*
 *  Copyright 2026 Alexey Andreev.
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
 *  Modified for Incari (tools/teavm-patches/README.md): ReferenceQueue.supply, which the
 *  finalization callback hands every collected reference to, got only WeakReference itself as
 *  its argument's type, not the subclasses actually made with a queue. The analysis then took
 *  poll()'s results for plain WeakReferences and left the subclasses' own methods out where
 *  they're called on them: Guava's weak-value maps (the game's ResourceKey interner) called
 *  WeakValueReference.getEntry() through an empty vtable slot and the page trapped. The types
 *  that reach the queue constructor's receiver now flow into supply().
 */
package org.teavm.backend.wasm.dependencies;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.DependencyNode;
import org.teavm.dependency.MethodDependency;
import org.teavm.model.MethodReference;

public class WasmGCReferenceQueueDependency extends AbstractDependencyListener {
    private DependencyNode valueNode;
    // Incari: the classes of references made with a queue, for supply()'s argument.
    private DependencyNode queuedReferenceNode;
    private boolean supplyLinked;
    private boolean refQueuePassedToRef;
    private boolean refQueuePoll;

    @Override
    public void started(DependencyAgent agent) {
        valueNode = agent.createNode();
        queuedReferenceNode = agent.createNode();
    }

    @Override
    public void methodReached(DependencyAgent agent, MethodDependency method) {
        if (method.getMethod().getOwnerName().equals("java.lang.ref.WeakReference")) {
            switch (method.getMethod().getName()) {
                case "<init>":
                    if (method.getMethod().parameterCount() == 2) {
                        method.getVariable(0).connect(queuedReferenceNode);
                        refQueuePassedToRef = true;
                        checkRefQueue(agent);
                    }
                    method.getVariable(1).connect(valueNode);
                    break;
                case "get":
                    valueNode.connect(method.getResult());
                    break;
            }
        } else if (method.getMethod().getOwnerName().equals(ReferenceQueue.class.getName())) {
            if (method.getMethod().getName().equals("poll")) {
                refQueuePoll = true;
                checkRefQueue(agent);
            }
        }
    }

    private void checkRefQueue(DependencyAgent agent) {
        if (refQueuePassedToRef && refQueuePoll && !supplyLinked) {
            supplyLinked = true;
            MethodDependency supply = agent.linkMethod(
                    new MethodReference(ReferenceQueue.class, "supply", Reference.class, void.class));
            supply.propagate(0, ReferenceQueue.class)
                    .propagate(1, WeakReference.class);
            queuedReferenceNode.connect(supply.getVariable(1));
            supply.use();
        }
    }
}
