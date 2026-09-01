//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo22 extends GhidraScript {
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
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        for (long d : new long[]{0x400bcd14L, 0x400bc030L, 0x400bc038L}) {
            println("\n==== refs to 0x" + Long.toHexString(d) + " ====");
            Set<Long> fns = new LinkedHashSet<>();
            for (Reference r : rm.getReferencesTo(toAddr(d))) {
                Function cf = fm.getFunctionContaining(r.getFromAddress());
                Instruction ix = getInstructionAt(r.getFromAddress());
                println("  " + r.getFromAddress() + "  " + r.getReferenceType() + "  "
                    + (cf!=null?cf.getName()+"@"+cf.getEntryPoint():"?") + "  " + (ix!=null?ix:""));
                if (cf != null) fns.add(cf.getEntryPoint().getOffset());
            }
        }
        // FUN_4005829c: the page builder -- learn how encoder handlers get registered
        dump(0x4005829cL);
        dump(0x400554e0L);
        // FUN_4007ec60 / FUN_4007edb0 -- widget register
        dump(0x4007ec60L);
    }
}
