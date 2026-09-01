//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo6 extends GhidraScript {
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void dump(long entry) throws Exception {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(entry));
        if (f == null) { println("\n// no function at 0x" + Long.toHexString(entry)); return; }
        if (!done.add(entry)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 200, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void callers(long entry) throws Exception {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(entry));
        println("\n=== callers of " + (f!=null?f.getName():Long.toHexString(entry)) + " ===");
        if (f==null) return;
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor()))
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        dump(0x400383e4L);
        dump(0x40038148L);
        dump(0x400381c8L);
        dump(0x40037f40L);
        callers(0x400383e4L);
        callers(0x40038148L);
        callers(0x400381c8L);
    }
}
