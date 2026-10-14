# web-classlib

- Write each class fresh from the JDK's documented contract. Never copy JDK or TeaVM source.
- Browser threads are coroutines: block with `wait`/`notify`, never spin. Stay correct on a real JVM.
- Signatures must match the JDK's once `T` names are read as JDK names (see README).
- Test: `./gradlew :web-classlib:check`.
- `memory/Heap` has two backends picked at compile time (`Heap.linear()`): linear memory in the
  browser, heap buffers on a JVM. Keep them in step, and keep `LinearMemory` free of allocation.
