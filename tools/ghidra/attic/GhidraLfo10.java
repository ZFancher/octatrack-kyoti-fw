//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo10 extends GhidraScript {
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void ensure(long e) throws Exception {
        Address a = toAddr(e);
        if (getInstructionAt(a) == null) disassemble(a);
        Function f = getFunctionAt(a);
        if (f == null) f = createFunction(a, null);
    }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null){ println("\n// could not make func @0x"+Long.toHexString(e)); return; }
        if (!done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 240, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        for (long e : new long[]{
            0x40058390L, 0x4003ad8cL, 0x40079c28L,
            0x400584d0L, 0x4005948cL, 0x40059620L, 0x400597b4L, 0x4005996cL, 0x40059afcL,
            0x40032640L
        }) dump(e);
    }
}
