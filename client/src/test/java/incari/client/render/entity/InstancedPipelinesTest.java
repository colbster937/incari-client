package incari.client.render.entity;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.testing.TestPlatform;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

/** Which of the game's pipelines get an instanced copy. */
class InstancedPipelinesTest {

    @Test
    void opaqueEntityPipelinesAreCopied() {
        TestPlatform.install();
        assertThat(InstancedPipelines.of(RenderPipelines.ARMOR_CUTOUT_NO_CULL)).isNotNull();
        assertThat(InstancedPipelines.of(RenderPipelines.ENTITY_CUTOUT)).isNotNull();
    }
}
