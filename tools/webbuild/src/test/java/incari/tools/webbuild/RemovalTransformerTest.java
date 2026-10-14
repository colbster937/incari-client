package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemovalTransformerTest {

    @Test
    void teavmLambdaClassesAreNeverMissing() {
        assertThat(RemovalTransformer.isGenerated("com.mojang.realmsclient.client.RealmsClient$<clinit>$lambda$_63_0"))
                .isTrue();
        assertThat(RemovalTransformer.isGenerated("net.example.Thing$run$lambda$_2_11"))
                .isTrue();
        assertThat(RemovalTransformer.isGenerated("net.example.Thing$lambda")).isFalse();
        assertThat(RemovalTransformer.isGenerated("net.example.Thing")).isFalse();
    }

    @Test
    void teavmAtomicUpdaterClassesAreNeverMissing() {
        assertThat(RemovalTransformer.isGenerated("io.netty.util.internal.RefCnt$value$_AtomicUpdater$"))
                .isTrue();
        assertThat(RemovalTransformer.isGenerated("net.example.Thing$_AtomicUpdater$Other"))
                .isFalse();
    }

    @Test
    void teavmAnnotationClassesAreNeverMissing() {
        assertThat(RemovalTransformer.isGenerated("io.netty.channel.ChannelHandler$Sharable$$_impl"))
                .isTrue();
        assertThat(RemovalTransformer.isGenerated("net.example.Thing$$_implementation"))
                .isFalse();
    }
}
