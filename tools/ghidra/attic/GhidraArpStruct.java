// Decompile all functions that access the arp struct fields, plus find the quantizer.
// @category Octatrack
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;

public class GhidraArpStruct extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Function> done = new java.util.LinkedHashSet<>();
    void dumpAt(long a) throws Exception {
        Function f = getFunctionContaining(toAddr(a));
        if (f == null) { println("no func @"+Long.toHexString(a)); return; }
        if (!done.add(f)) return;
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " (via "+Long.toHexString(a)+") ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr != null && dr.getDecompiledFunction() != null)
            println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        long[] addrs = {
            0x40026208L,0x40027b66L,0x40027c8eL,0x40079b3eL,0x40079df8L,0x4007a824L,0x4007ae28L,
            0x40026294L,0x40027b76L,0x40027c9eL,0x4007a424L,0x4007aea6L,
            0x400265f8L,0x40028d8cL,0x4003faceL,0x4005970eL,0x4005dcfaL,
            0x40026634L,0x40028ec8L,0x4003f5aaL,0x4005957aL,0x4005de88L
        };
        for (long a : addrs) dumpAt(a);
    }
}
