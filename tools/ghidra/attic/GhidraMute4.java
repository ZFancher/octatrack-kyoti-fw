// GhidraMute4.java -- trace how the mute mask reaches the audio engine.
//  - readers/writers of _DAT_46c7ff64, _DAT_46c803d4, _DAT_8000184e, _DAT_8000184c (mute-derived masks)
//  - voice helpers FUN_400042b4, FUN_40000e50, FUN_40008f84, FUN_40008fe4
//  - the arranger row-apply path: callers of FUN_40083ab4 / FUN_40083e40 (mute set/clear)
//  - FUN_400836d8 already have; get FUN_40083208, FUN_40083bf8

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute4 extends GhidraScript {
    DecompInterface dec;
    ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm;
    AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();

    String decomp(Function f) {
        try {
            DecompileResults dr = dec.decompileFunction(f, 150, mon);
            if (dr != null && dr.getDecompiledFunction() != null) return dr.getDecompiledFunction().getC();
        } catch (Exception e) {}
        return "  <decompile failed>";
    }
    void dumpFunc(Function f, String tag) {
        if (f == null) { println("  (no function -- " + tag + ")"); return; }
        if (!dumped.add(f.getEntryPoint().getOffset())) { println("// (dup " + f.getName() + " -- " + tag + ")"); return; }
        println("\n########## " + f.getName() + " @" + f.getEntryPoint()
                + "  size=" + f.getBody().getNumAddresses() + "  (" + tag + ") ##########");
        println(decomp(f));
    }
    void scalarScan(long lo, long hi, String label, boolean dump) {
        println("\n==================== scalar scan " + label + " ====================");
        InstructionIterator ii = currentProgram.getListing().getInstructions(true);
        TreeSet<Long> fns = new TreeSet<>();
        int hits = 0;
        while (ii.hasNext()) {
            Instruction ins = ii.next();
            for (int op = 0; op < ins.getNumOperands(); op++)
                for (Object o : ins.getOpObjects(op)) {
                    long v = Long.MIN_VALUE;
                    if (o instanceof Scalar) v = ((Scalar) o).getUnsignedValue();
                    else if (o instanceof Address) v = ((Address) o).getOffset();
                    if (v >= lo && v <= hi) {
                        Function cf = fm.getFunctionContaining(ins.getAddress());
                        println("  " + ins.getAddress() + "  " + ins + "   [" + (cf != null ? cf.getName() : "?") + "]");
                        if (cf != null) fns.add(cf.getEntryPoint().getOffset());
                        hits++;
                    }
                }
        }
        if (hits == 0) println("  (none)");
        if (dump) for (Long a : fns) dumpFunc(fm.getFunctionContaining(sp.getAddress(a)), "touches " + label);
    }
    void callersOf(long addr, String label) {
        println("\n========== callers of " + label + " @0x" + Long.toHexString(addr) + " ==========");
        ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(addr));
        TreeSet<Long> fns = new TreeSet<>();
        while (it.hasNext()) {
            Reference r = it.next();
            Function cf = fm.getFunctionContaining(r.getFromAddress());
            println("  from " + r.getFromAddress() + " in " + (cf != null ? cf.getName() : "NOFUNC") + " " + r.getReferenceType());
            if (cf != null) fns.add(cf.getEntryPoint().getOffset());
        }
        for (Long a : fns) dumpFunc(fm.getFunctionContaining(sp.getAddress(a)), "caller of " + label);
    }

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec = new DecompInterface(); dec.openProgram(currentProgram);

        for (long[] p : new long[][]{ {0x46c7ff64L}, {0x46c803d4L}, {0x8000184eL}, {0x8000184cL} })
            scalarScan(p[0], p[0], "0x" + Long.toHexString(p[0]), true);

        for (long a : new long[]{0x400042b4L, 0x40000e50L, 0x40008f84L, 0x40008fe4L, 0x40083208L, 0x40083bf8L})
            dumpFunc(fm.getFunctionContaining(sp.getAddress(a)), "voice/mute helper");

        callersOf(0x40083ab4L, "FUN_40083ab4 mute-set");
        callersOf(0x40083e40L, "FUN_40083e40 mute-clear");
        callersOf(0x40083480L, "FUN_40083480 getmask");

        dec.dispose();
        println("\n[GhidraMute4] done.");
    }
}
