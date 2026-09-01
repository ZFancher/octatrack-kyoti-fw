//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo12 extends GhidraScript {
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void ensure(long e) throws Exception { Address a=toAddr(e); if(getInstructionAt(a)==null) disassemble(a); if(getFunctionAt(a)==null) createFunction(a,null); }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null || !done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 260, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        ReferenceManager rm = currentProgram.getReferenceManager();
        FunctionManager fm = currentProgram.getFunctionManager();

        for (long d : new long[]{0x400bbc72L}) {
            println("=== refs to DAT_" + Long.toHexString(d) + " ===");
            for (Reference r : rm.getReferencesTo(toAddr(d))) {
                Function cf = fm.getFunctionContaining(r.getFromAddress());
                Instruction ix = getInstructionAt(r.getFromAddress());
                println("  " + r.getFromAddress() + "  " + r.getReferenceType() + "  " + (cf!=null?cf.getName():"?")
                    + (ix!=null?"   ["+ix+"]":""));
            }
        }
        dump(0x40054cd8L);
        dump(0x400526e4L);
        dump(0x40052474L);
    }
}
