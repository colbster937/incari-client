package incari.client.datafix;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.util.datafix.DataFixers;

/**
 * The game's data fixer, built the first time something needs upgrading. Data already at (or
 * past) the version it's being upgraded to comes back untouched, as the real fixer returns it,
 * without building anything; everything else goes to the real fixer.
 */
final class LazyDataFixer implements DataFixer {

    static final LazyDataFixer INSTANCE = new LazyDataFixer();

    private LazyDataFixer() {}

    @Override
    public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion) {
        return version >= newVersion ? input : DataFixers.getDataFixer().update(type, input, version, newVersion);
    }

    @Override
    public Schema getSchema(int key) {
        return DataFixers.getDataFixer().getSchema(key);
    }
}
