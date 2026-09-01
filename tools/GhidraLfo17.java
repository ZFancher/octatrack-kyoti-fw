//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo17 extends GhidraScript {
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void ensure(long e) throws Exception { Address a=toAddr(e); if(getInstructionAt(a)==null) disassemble(a); if(getFunctionAt(a)==null) createFunction(a,null); }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null || !done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 300, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void callers(long e, boolean dumpThem) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        println("\n=== callers of " + (f!=null?f.getName():Long.toHexString(e)) + " ===");
        if (f==null) return;
        List<Long> cs = new ArrayList<>();
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor())) {
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
            cs.add(c.getEntryPoint().getOffset());
        }
        if (dumpThem) for (long c : cs) dump(c);
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        // full FUN_40033e3c
        dump(0x40033e3cL);
        callers(0x40033e3cL, false);
        // FUN_400a14f0 callers, FUN_40054cd8 already known (FUN_400a14f0, FUN_40061a94)
        callers(0x40054cd8L, false);
        callers(0x40055008L, true);
    }
}
