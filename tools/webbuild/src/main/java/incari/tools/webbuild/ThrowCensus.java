package incari.tools.webbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.RaiseInstruction;

/**
 * Counts the program's throws (`-Pteavm.throwCensus`, development only). In the page a throw caught
 * on any thread but the main one costs about ten times what it costs on the main one (ADR 0033,
 * {@code app-web/probes/PacketHandoffProbe}), so exceptions thrown as a matter of course off the
 * main thread are worth finding. Before every {@code throw} in the program (rethrows too) this
 * inserts a call to the class library's {@code incari.classlib.ThrowCensus.thrown(exception, site)},
 * which counts throws by site and thread and logs the busiest; the sites are numbered here, and
 * their methods written to {@code throw-sites.txt} beside the build.
 *
 * <p>Throws the backend makes itself (a null check's NullPointerException, an array index's,
 * a cast's) and exceptions from JavaScript aren't counted: they have no {@code throw} instruction.
 */
public final class ThrowCensus implements ClassHolderTransformer {

    /** The class library's counter, which the web build refers to by name. */
    static final String COUNTER = "incari.classlib.ThrowCensus";

    private static final MethodReference THROWN = new MethodReference(
            COUNTER, "thrown", ValueType.object("java.lang.Object"), ValueType.INTEGER, ValueType.VOID);

    private static final List<String> SITES = new ArrayList<>();

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (cls.getName().equals(COUNTER)) {
            // Its own throws would count themselves.
            return;
        }
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram();
            if (program == null) {
                continue;
            }
            for (int i = 0; i < program.basicBlockCount(); i++) {
                BasicBlock block = program.basicBlockAt(i);
                for (Instruction insn = block.getFirstInstruction(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof RaiseInstruction raise) {
                        count(program, raise, site(cls.getName() + "." + method.getName()));
                    }
                }
            }
        }
    }

    private static synchronized int site(String method) {
        SITES.add(method);
        return SITES.size() - 1;
    }

    private static void count(Program program, RaiseInstruction raise, int site) {
        Variable id = program.createVariable();
        IntegerConstantInstruction constant = new IntegerConstantInstruction();
        constant.setConstant(site);
        constant.setReceiver(id);
        constant.setLocation(raise.getLocation());
        InvokeInstruction call = new InvokeInstruction();
        call.setType(InvocationType.SPECIAL);
        call.setMethod(THROWN);
        call.setArguments(raise.getException(), id);
        call.setLocation(raise.getLocation());
        raise.insertPrevious(constant);
        raise.insertPrevious(call);
    }

    /** Writes the sites, one a line, numbered by line (from 0). */
    static synchronized void write(Path file) throws IOException {
        Files.write(file, SITES);
        SITES.clear();
    }
}
