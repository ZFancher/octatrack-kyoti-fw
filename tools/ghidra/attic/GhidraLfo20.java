//@category Octatrack
// Pin down _DAT_80000012 / DAT_80000012 / DAT_80000013 semantics: every instruction that
// reads or writes absolute 0x80000012..0x80000015, with size, and the containing function.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.scalar.Scalar;
import java.util.*;

public class GhidraLfo20 extends GhidraScript {
    public void run() throws Exception {
        Listing lst = currentProgram.getListing();
        InstructionIterator it = lst.getInstructions(true);
        Set<Long> targets = new HashSet<>();
        for (long a = 0x80000010L; a <= 0x80000017L; a++) targets.add(a);
        while (it.hasNext()) {
            Instruction ins = it.next();
            for (int op = 0; op < ins.getNumOperands(); op++) {
                for (Object o : ins.getOpObjects(op)) {
                    long v = -1;
                    if (o instanceof Scalar) v = ((Scalar)o).getUnsignedValue();
                    else if (o instanceof Address) v = ((Address)o).getOffset();
                    if (targets.contains(v)) {
                        Function f = getFunctionContaining(ins.getAddress());
                        println(String.format("  0x%08x  %-40s  in %s", ins.getAddress().getOffset(),
                            ins.toString(), f!=null?f.getName()+"@"+f.getEntryPoint():"?"));
                    }
                }
            }
        }
    }
}
