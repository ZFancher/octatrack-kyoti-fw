// Decompile arp-engine functions that READ the voice-descriptor struct (0x460c8122 / 0x460bf218).
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;

public class GhidraArpEng extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Long> done = new java.util.LinkedHashSet<>();
    void dump(long entry) throws Exception {
        Function f = getFunctionContaining(toAddr(entry));
        if (f==null){ println("no func @"+Long.toHexString(entry)); return; }
        if (!done.add(f.getEntryPoint().getOffset())) return;
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr!=null && dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());
        else println("<fail>");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        long[] entries = {
            0x4002788eL,0x40029f90L,0x4002a11cL,0x4002a664L,0x4002a7f0L,0x4002bb14L,
            0x40029ea8L,0x4002a034L,0x4002a57cL,0x4002a708L,0x4002bac0L
        };
        for (long e : entries) dump(e);
    }
}
