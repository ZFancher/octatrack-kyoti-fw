// GhidraMute2.java -- enumerate every caller of the voice-command primitive FUN_40005178
// and of FUN_40005030, with call-site context, and decompile each distinct caller.
// Also decompile FUN_40005030 and FUN_40005178 helpers, and dump disasm around the
// "QUICK MUTE" data reference at 0x400d0240.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute2 extends GhidraScript {
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
        if (!dumped.add(f.getEntryPoint().getOffset())) {
            println("// (already dumped " + f.getName() + " -- " + tag + ")");
            return;
        }
        println("\n########## " + f.getName() + " @" + f.getEntryPoint()
                + "  size=" + f.getBody().getNumAddresses() + "  (" + tag + ") ##########");
        println(decomp(f));
    }

    void callersOf(long addr, String label) {
        println("\n==================== callers of " + label + " @0x" + Long.toHexString(addr) + " ====================");
        Address a = sp.getAddress(addr);
        ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(a);
        List<Function> fns = new ArrayList<>();
        while (it.hasNext()) {
            Reference r = it.next();
            Address from = r.getFromAddress();
            Function cf = fm.getFunctionContaining(from);
            println("\n  --- from " + from + " in " + (cf != null ? cf.getName() + "@" + cf.getEntryPoint() : "NOFUNC")
                    + "  " + r.getReferenceType());
            // context: 8 insns before, 3 after
            Address cur = from;
            for (int i = 0; i < 8; i++) { Instruction p = getInstructionBefore(cur); if (p == null) break; cur = p.getAddress(); }
            for (int i = 0; i < 14; i++) {
                Instruction cx = getInstructionAt(cur); if (cx == null) break;
                println((cx.getAddress().equals(from) ? "  >>> " : "      ") + cx.getAddress() + "  " + cx.toString());
                if (cx.getAddress().compareTo(from) >= 0 && i > 3) {
                    Instruction nx = getInstructionAfter(cur);
                    for (int k = 0; k < 2 && nx != null; k++) { println("      " + nx.getAddress() + "  " + nx.toString()); nx = getInstructionAfter(nx.getAddress()); }
                    break;
                }
                Instruction nx = getInstructionAfter(cur); if (nx == null) break; cur = nx.getAddress();
            }
            if (cf != null) fns.add(cf);
        }
        for (Function f : fns) dumpFunc(f, "caller of " + label);
    }

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec = new DecompInterface();
        dec.openProgram(currentProgram);

        Function f30 = fm.getFunctionContaining(sp.getAddress(0x40005030L));
        dumpFunc(f30, "FUN_40005030");
        Function f78 = fm.getFunctionContaining(sp.getAddress(0x40005178L));
        dumpFunc(f78, "FUN_40005178");

        callersOf(0x40005178L, "FUN_40005178 voice-cmd");
        callersOf(0x40005030L, "FUN_40005030");

        // disasm around the QUICK MUTE data ref
        println("\n==================== disasm around 0x400d0200..0x400d0280 (QUICK MUTE ref site) ====================");
        Address d = sp.getAddress(0x400d0200L);
        for (int i = 0; i < 48; i++) {
            Instruction ins = getInstructionAt(d);
            Data dat = getDataAt(d);
            if (ins != null) { println("  " + d + "  " + ins.toString()); d = ins.getMaxAddress().next(); }
            else if (dat != null) { println("  " + d + "  DATA " + dat.toString()); d = dat.getMaxAddress().next(); }
            else { println("  " + d + "  ??"); d = d.next(); }
        }

        dec.dispose();
        println("\n[GhidraMute2] done.");
    }
}
