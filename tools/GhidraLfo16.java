//@category Octatrack
// FUN_400409f4 is the polled "AUDIO CC OUT" transmitter: for each set bit `ch` in
// _DAT_46c7e0de, for each set param bit in [ch*0x10 + 0x46c7d7d8], transmit
// CC (iVar5+bit) = value[ch*0x80 + 0x46c7bf2c + ...] with status (ch | 0xB0).
// Find every writer of _DAT_46c7e0de and the param-pending arrays -> the enqueue site(s),
// one of which mis-keys MIDI-LFO-SETUP edits to the twin audio track's channel.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo16 extends GhidraScript {
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void ensure(long e) throws Exception { Address a=toAddr(e); if(getInstructionAt(a)==null) disassemble(a); if(getFunctionAt(a)==null) createFunction(a,null); }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null || !done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 260, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void refs(long lo, long hi, String tag) {
        println("\n==== refs into " + tag + " 0x" + Long.toHexString(lo) + "..0x" + Long.toHexString(hi) + " ====");
        ReferenceManager rm = currentProgram.getReferenceManager();
        FunctionManager fm = currentProgram.getFunctionManager();
        Set<Long> fns = new LinkedHashSet<>();
        for (long a = lo; a < hi; a++) {
            for (Reference r : rm.getReferencesTo(toAddr(a))) {
                Function cf = fm.getFunctionContaining(r.getFromAddress());
                Instruction ix = getInstructionAt(r.getFromAddress());
                println(String.format("  0x%08x  %-6s  %-24s  %s", a, r.getReferenceType(),
                    cf!=null?cf.getName()+"@"+cf.getEntryPoint():"?", ix!=null?ix.toString():""));
                if (cf != null && r.getReferenceType().isWrite()) fns.add(cf.getEntryPoint().getOffset());
            }
        }
        callerDump.addAll(fns);
    }
    Set<Long> callerDump = new LinkedHashSet<>();
    public void run() throws Exception {
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        refs(0x46c7e0deL, 0x46c7e0e2L, "_DAT_46c7e0de (pending-channel mask)");
        refs(0x46c7d7d8L, 0x46c7d7e0L, "pending-param bitmask base");
        refs(0x46c7bf2cL, 0x46c7bf34L, "pending-value array base");
        for (long e : callerDump) dump(e);
    }
}
