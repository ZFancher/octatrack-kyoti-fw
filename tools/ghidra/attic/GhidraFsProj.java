// Decompile project-selection / name-populate helpers.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.Address;

public class GhidraFsProj extends ghidra.app.script.GhidraScript {
    long[] FUNCS = { 0x40025650L, 0x400255ecL, 0x40025288L };
    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        for (long fa : FUNCS) {
            Function f = getFunctionAt(toAddr(fa));
            if (f == null) { println("NO FUNC @" + toAddr(fa)); continue; }
            println("\n##### " + f.getName() + " @" + f.getEntryPoint() + " #####");
            DecompileResults dr = dec.decompileFunction(f, 90, monitor);
            if (dr != null && dr.getDecompiledFunction() != null)
                println(dr.getDecompiledFunction().getC());
        }
        // find any instruction that copies INTO 0x100f8378 (populate site): look for
        // functions referencing both 0x100f8378 and a memcpy/strcpy. Just list writers.
        println("\n=== all refs to 0x100f8378 with type ===");
        ReferenceManager rm = currentProgram.getReferenceManager();
        for (Reference r : rm.getReferencesTo(toAddr(0x100f8378L))) {
            Function f = getFunctionContaining(r.getFromAddress());
            println("  " + r.getFromAddress()+" ["+r.getReferenceType()+"] "+(f==null?"":f.getName()));
        }
    }
}
