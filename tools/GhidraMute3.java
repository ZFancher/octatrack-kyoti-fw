// GhidraMute3.java -- locate the audio-track mute mask + its readers/writers, the
// per-machine voice dispatch table (FUN_40083a30), and the DSP frame builder.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute3 extends GhidraScript {
    DecompInterface dec;
    ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm;
    AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();

    String decomp(Function f) {
        try {
            DecompileResults dr = dec.decompileFunction(f, 150, mon);
            if (dr != null && dr.getDecompiledFunction() != null)
                return dr.getDecompiledFunction().getC();
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

    // scan every instruction for a scalar operand within [lo,hi]
    void scalarScan(long lo, long hi, String label) {
        println("\n==================== scalar scan " + label + " [0x" + Long.toHexString(lo)
                + " .. 0x" + Long.toHexString(hi) + "] ====================");
        InstructionIterator ii = currentProgram.getListing().getInstructions(true);
        TreeSet<Long> fns = new TreeSet<>();
        int hits = 0;
        while (ii.hasNext()) {
            Instruction ins = ii.next();
            int nops = ins.getNumOperands();
            for (int op = 0; op < nops; op++) {
                Object[] objs = ins.getOpObjects(op);
                for (Object o : objs) {
                    long v = Long.MIN_VALUE;
                    if (o instanceof Scalar) v = ((Scalar) o).getUnsignedValue();
                    else if (o instanceof Address) v = ((Address) o).getOffset();
                    if (v >= lo && v <= hi) {
                        Function cf = fm.getFunctionContaining(ins.getAddress());
                        println("  " + ins.getAddress() + "  " + ins.toString()
                                + "   [" + (cf != null ? cf.getName() : "?") + "]");
                        if (cf != null) fns.add(cf.getEntryPoint().getOffset());
                        hits++;
                    }
                }
            }
        }
        if (hits == 0) println("  (none)");
        for (Long a : fns) dumpFunc(fm.getFunctionContaining(sp.getAddress(a)), "touches " + label);
    }

    void ensureFunc(long a, String nm) {
        Address ad = sp.getAddress(a);
        if (fm.getFunctionContaining(ad) == null) {
            try { disassemble(ad); createFunction(ad, nm); } catch (Exception e) { println("mkfunc fail @" + ad + ": " + e); }
        }
    }

    void run2() throws Exception {}
    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec = new DecompInterface();
        dec.openProgram(currentProgram);

        // per-machine voice dispatch tables
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x40083a30L)), "FUN_40083a30 (dispatch)");
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x400836d8L)), "FUN_400836d8");
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x40083544L)), "FUN_40083544");

        // frame builder
        ensureFunc(0x4000c8a4L, "frame_builder");
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x4000c8a4L)), "frame builder 4000c8a4");

        // the live mute/solo mask cluster
        scalarScan(0x460fab38L, 0x460fab52L, "460fabXX live-perf mask cluster");

        dec.dispose();
        println("\n[GhidraMute3] done.");
    }
}
