// Find references to a queue/data address and the function containing each ref.
//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraBankRefs extends GhidraScript {
    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        ReferenceManager rm = currentProgram.getReferenceManager();
        String[] args = getScriptArgs();
        java.util.LinkedHashSet<Function> funcs = new java.util.LinkedHashSet<>();
        for (String s : args) {
            long t = Long.parseLong(s.replace("0x",""),16);
            Address a = toAddr(t);
            println("=== direct refs to " + a + " ===");
            for (Reference r : rm.getReferencesTo(a)) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  from " + r.getFromAddress() + " ["+r.getReferenceType()+"] in " + (f==null?"<none>":f.getName()+"@"+f.getEntryPoint()));
                if (f!=null) funcs.add(f);
            }
            // pointer literal in memory (4-byte BE)
            String pat = String.format("%02x %02x %02x %02x",(t>>24)&0xff,(t>>16)&0xff,(t>>8)&0xff,t&0xff);
            Address[] hits = findBytes(null, pat.replace(" ",""), 20);
            for (Address da : hits) {
                // who references this literal location
                println("  literal@" + da);
                for (Reference r : rm.getReferencesTo(da)) {
                    Function f = getFunctionContaining(r.getFromAddress());
                    println("     used-by " + r.getFromAddress() + " in " + (f==null?"<none>":f.getName()));
                    if (f!=null) funcs.add(f);
                }
            }
        }
        boolean decompile = System.getenv("DECOMP") != null;
        if (decompile) {
            for (Function f : funcs) {
                DecompileResults dr = dec.decompileFunction(f, 90, mon);
                println("\n##### " + f.getName() + " @" + f.getEntryPoint() + " #####");
                if (dr!=null && dr.decompileCompleted()) println(dr.getDecompiledFunction().getC());
            }
        }
        println("[done]");
    }
}
