// Find the ARP page dispatch: callers of renderer FUN_40079d48, and neighbors.
// Also dump the descriptor tables the renderer indexes (0x400d40b2 region).
// @category Octatrack
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.mem.Memory;

public class GhidraArpPage extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    void dumpFunc(Function f) throws Exception {
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr != null && dr.getDecompiledFunction() != null)
            println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ReferenceManager rm = currentProgram.getReferenceManager();

        println("=== callers/refs to renderer 40079d48 ===");
        java.util.LinkedHashSet<Function> funcs = new java.util.LinkedHashSet<>();
        for (Reference r : rm.getReferencesTo(toAddr(0x40079d48L))) {
            Function f = getFunctionContaining(r.getFromAddress());
            println("  from " + r.getFromAddress() + " (" + r.getReferenceType() + ") in " + (f==null?"<none>":f.getName()+" @"+f.getEntryPoint()));
            if (f != null) funcs.add(f);
        }
        // Also who references the descriptor table base and neighbors
        long[] datrefs = { 0x400d40b2L, 0x400d400aL, 0x400d415aL, 0x400d3f00L };
        for (long d : datrefs) {
            println("\n=== refs to DAT " + Long.toHexString(d) + " ===");
            for (Reference r : rm.getReferencesTo(toAddr(d))) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  from " + r.getFromAddress() + " in " + (f==null?"<none>":f.getName()+" @"+f.getEntryPoint()));
            }
        }
        for (Function f : funcs) dumpFunc(f);
    }
}
