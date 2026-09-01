//@category Octatrack
// Dump decompilation of every caller of the MIDI-send primitive FUN_40010bc8, plus the
// primitive itself and its immediate wrappers, so we can grep for the LFO CC path
// (base CC 0x1c..0x21) and the channel-selection logic.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo4 extends GhidraScript {
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void dump(Function f) throws Exception {
        if (f == null || !done.add(f.getEntryPoint().getOffset())) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 160, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function prim = fm.getFunctionAt(toAddr(0x40010bc8L));
        dump(prim);
        for (Function c : prim.getCallingFunctions(new ConsoleTaskMonitor())) dump(c);
    }
}
