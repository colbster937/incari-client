package incari.client.datafix;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.Test;

class LazyDataFixerTest {

    @Test
    void passesCurrentDataThroughWithoutBuildingTheFixer() {
        Dynamic<?> input = new Dynamic<>(JsonOps.INSTANCE, new JsonPrimitive("options"));
        assertThat(LazyDataFixer.INSTANCE.update(References.OPTIONS, input, 4000, 4000))
                .isSameAs(input);
        assertThat(LazyDataFixer.INSTANCE.update(References.OPTIONS, input, 4100, 4000))
                .isSameAs(input);
    }
}
