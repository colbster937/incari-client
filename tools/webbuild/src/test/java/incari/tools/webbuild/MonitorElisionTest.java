package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;

class MonitorElisionTest {

    private static MethodReference method(String owner, String nameAndDescriptor) {
        return new MethodReference(owner, MethodDescriptor.parse(nameAndDescriptor));
    }

    @Test
    void monitorMethodsAndBlockingApisBlock() {
        assertThat(MonitorElision.isBlocking(method("net.example.Anything", "wait()V")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method("java.lang.Object", "wait(J)V")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method("net.example.Anything", "notifyAll()V")))
                .isFalse();
        assertThat(MonitorElision.isNotify(method("net.example.Anything", "notifyAll()V")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method("java.lang.Thread", "sleep(J)V")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(
                        method("java.util.concurrent.CompletableFuture", "join()Ljava/lang/Object;")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method(
                        "java.util.concurrent.locks.ReentrantLock", "tryLock(JLjava/util/concurrent/TimeUnit;)Z")))
                .isTrue();
        // lock() is handled apart from blocking: see MonitorElision.isLock.
        assertThat(MonitorElision.isBlocking(method("java.util.concurrent.locks.ReentrantLock", "lock()V")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(
                        method("java.util.concurrent.LinkedBlockingQueue", "take()Ljava/lang/Object;")))
                .isTrue();
    }

    @Test
    void ordinaryCallsDoNot() {
        assertThat(MonitorElision.isBlocking(
                        method("java.util.concurrent.ConcurrentHashMap", "get(Ljava/lang/Object;)Ljava/lang/Object;")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(method("java.util.Map", "get(Ljava/lang/Object;)Ljava/lang/Object;")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(method("net.example.Anything", "wait(I)V")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(method("java.util.concurrent.locks.ReentrantLock", "tryLock()Z")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(method("java.lang.Thread", "getName()Ljava/lang/String;")))
                .isFalse();
    }

    @Test
    void queuesBlockOnlyInTheirWaitingForms() {
        assertThat(MonitorElision.isBlocking(
                        method("java.util.concurrent.BlockingQueue", "offer(Ljava/lang/Object;)Z")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(method("java.util.concurrent.BlockingQueue", "poll()Ljava/lang/Object;")))
                .isFalse();
        assertThat(MonitorElision.isBlocking(method(
                        "java.util.concurrent.BlockingQueue",
                        "offer(Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Z")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method(
                        "java.util.concurrent.BlockingQueue",
                        "poll(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method("java.util.concurrent.BlockingQueue", "put(Ljava/lang/Object;)V")))
                .isTrue();
        assertThat(MonitorElision.isBlocking(method("java.util.concurrent.BlockingQueue", "take()Ljava/lang/Object;")))
                .isTrue();
    }
}
