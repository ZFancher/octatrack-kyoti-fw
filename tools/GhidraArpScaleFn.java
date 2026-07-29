// Decompile the scale-label functions at 0x4003b7a6/0x4003b7bc and callers.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class GhidraArpScaleFn extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Function> done = new java.util.LinkedHashSet<>();
    void dump(Function f) throws Exception {
        if (f==null || !done.add(f)) return;
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr!=null && dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());
        else println("<fail>");
    }
    void callersOf(Function f) {
        if (f==null) return;
        ReferenceManager rm = currentProgram.getReferenceManager();
        println("=== callers of "+f.getName()+" @"+f.getEntryPoint()+" ===");
        for (Reference r : rm.getReferencesTo(f.getEntryPoint())) {
            Function c = getFunctionContaining(r.getFromAddress());
            println("  from " + r.getFromAddress() + " ("+r.getReferenceType()+") in " + (c==null?"<none>":c.getName()+" @"+c.getEntryPoint()));
        }
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        long[] addrs = {0x4003b7a6L,0x4003b7bcL,0x4003b712L,0x4003b878L};
        java.util.LinkedHashSet<Function> fns = new java.util.LinkedHashSet<>();
        for (long a : addrs) {
            Function f = getFunctionContaining(toAddr(a));
            println("addr "+Long.toHexString(a)+" -> "+(f==null?"none":f.getName()+" @"+f.getEntryPoint()));
            if (f!=null) fns.add(f);
        }
        for (Function f : fns) callersOf(f);
        for (Function f : fns) dump(f);
    }
}
