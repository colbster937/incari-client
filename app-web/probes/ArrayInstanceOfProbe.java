import com.viaversion.viaversion.api.minecraft.item.HashedStructuredItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;

/**
 * Regression probe for Via's StructuredItemRewriter: it tells a data component's value
 * apart with {@code value instanceof Item[]}. Prints "probe passed" when arrays of other element
 * types (and an interface array of another interface) don't answer true, and real item arrays do.
 */
public final class ArrayInstanceOfProbe {

    interface Other {}

    private ArrayInstanceOfProbe() {}

    public static void main(String[] args) {
        Object items = new StructuredItem[] {StructuredItem.empty()};
        Object itemIface = new Item[] {StructuredItem.empty()};
        Object hashed = new HashedStructuredItem[] {HashedStructuredItem.empty()};
        Object others = new Other[] {new Other() {}};
        Object strings = new String[] {"a"};
        Object objects = new Object[] {"a"};
        Object nested = new StructuredItem[][] {{StructuredItem.empty()}};
        Object ints = new int[1];
        Object intsNested = new int[1][1];
        String more = "" + (nested instanceof Item[][]) + (nested instanceof Item[]) + (nested instanceof Other[][])
                + (ints instanceof int[]) + (ints instanceof Object[]) + (intsNested instanceof int[][])
                + (intsNested instanceof Item[][]) + (strings instanceof Object[]) + (null instanceof Item[]);
        String result = "" + (items instanceof Item[]) + (itemIface instanceof Item[]) + (hashed instanceof Item[])
                + (others instanceof Item[]) + (strings instanceof Item[]) + (objects instanceof Item[]);
        System.out.println((result + more).equals("truetruefalsefalsefalsefalse" + "truefalsefalsetruefalsetruefalsetruefalse") ? "probe passed" : "probe failed: " + result + " " + more);
    }
}
