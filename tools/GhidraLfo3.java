//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraLfo3 extends GhidraScript {
    DecompInterface dec;
    void dump(long entry) throws Exception {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(entry));
        if (f == null) { println("\n// no function at 0x" + Long.toHexString(entry)); return; }
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
        // LFO SETUP page controllers (callers of the renderer FUN_400572e8)
        dump(0x40057b54L);
        dump(0x40057c08L);
        callers(0x40057b54L);
        callers(0x40057c08L);
        // audio encoder editor #1 -- expect the AUDIO CC OUT gate + CC send helper
        dump(0x40052e98L);
        callers(0x40052e98L);
    }
}
