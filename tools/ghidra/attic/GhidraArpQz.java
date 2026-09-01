// Find readers of the runtime scale globals -> the quantizer.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class GhidraArpQz extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Function> done = new java.util.LinkedHashSet<>();
    void dump(Function f, long via) throws Exception {
        if (f==null) { println("no func via "+Long.toHexString(via)); return; }
        if (!done.add(f)) { println("(dup) "+f.getName()+" via "+Long.toHexString(via)); return; }
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " (via "+Long.toHexString(via)+") ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr!=null && dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());
        else println("<fail>");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        ReferenceManager rm = currentProgram.getReferenceManager();
        long[] globals = {0x460c8138L, 0x460bf22eL, 0x460c8134L, 0x460c8122L, 0x460bf22aL, 0x460bf218L};
        for (long g : globals) {
            println("\n=== refs to global "+Long.toHexString(g)+" ===");
            for (Reference r : rm.getReferencesTo(toAddr(g))) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  "+r.getFromAddress()+" ("+r.getReferenceType()+") "+(f==null?"<none>":f.getName()+" @"+f.getEntryPoint()));
            }
        }
        // decompile the readers of the two scale globals
        for (long g : new long[]{0x460c8138L,0x460bf22eL}) {
            for (Reference r : rm.getReferencesTo(toAddr(g))) {
                dump(getFunctionContaining(r.getFromAddress()), r.getFromAddress().getOffset());
            }
        }
    }
}
