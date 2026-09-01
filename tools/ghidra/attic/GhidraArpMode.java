// Decompile function at 0x4003b88e (references arp mode table) and its callers.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.*;

public class GhidraArpMode extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Function> done = new java.util.LinkedHashSet<>();
    void dump(Function f) throws Exception {
        if (f==null || !done.add(f)) return;
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr!=null && dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());
        else println("<fail>");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        ReferenceManager rm = currentProgram.getReferenceManager();
        Function f = getFunctionContaining(toAddr(0x4003b88eL));
        println("func containing 4003b88e = " + (f==null?"none":f.getName()+" @"+f.getEntryPoint()));
        // callers
        if (f!=null) {
            println("=== callers ===");
            for (Reference r : rm.getReferencesTo(f.getEntryPoint())) {
                Function c = getFunctionContaining(r.getFromAddress());
                println("  from " + r.getFromAddress() + " (" + r.getReferenceType() + ") in " + (c==null?"<none>":c.getName()+" @"+c.getEntryPoint()));
            }
        }
        dump(f);
    }
}
