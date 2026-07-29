// Find scale-label render path via MAJ/MIN suffix strings.
// @category Octatrack
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraArpMaj extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Function> funcs = new java.util.LinkedHashSet<>();
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
        long[] strs = { 0x400b4419L /*MIN*/, 0x400b5750L /*MAJ*/, 0x400b5366L /*CHROMATIC*/, 0x400b7868L /*SCALE MODE*/ };
        for (long t : strs) {
            Address a = toAddr(t);
            println("=== refs to " + a + " ===");
            for (Reference r : rm.getReferencesTo(a)) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  from " + r.getFromAddress() + " (" + r.getReferenceType() + ") in " + (f==null?"<none>":f.getName()+" @"+f.getEntryPoint()));
                if (f != null) funcs.add(f);
            }
        }
        for (Function f : funcs) dumpFunc(f);
    }
}
