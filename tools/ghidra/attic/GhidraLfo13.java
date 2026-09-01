//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo13 extends GhidraScript {
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
    void callers(long e) throws Exception {
        Function f = getFunctionAt(toAddr(e));
        println("\n=== callers of " + (f!=null?f.getName():Long.toHexString(e)) + " ===");
        if (f==null) return;
        List<Long> cs = new ArrayList<>();
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor())) {
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
            cs.add(c.getEntryPoint().getOffset());
        }
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        callers(0x40054cd8L);
        callers(0x4009eec8L);
        callers(0x4009da20L);
        // dump the callers of FUN_40054cd8
        Function f = getFunctionAt(toAddr(0x40054cd8L));
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor())) dump(c.getEntryPoint().getOffset());
    }
}
