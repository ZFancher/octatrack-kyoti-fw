// GhidraMute1.java -- New exploration: audio-track mute (FUNC+TRACK) vs arranger row mute.
// Goal: find (a) the FUNC+TRACK mute toggle + where the mute bitmask lives,
//       (b) how mute is applied to the audio engine (hard voice-stop vs gain gate),
//       (c) the arranger "MUTE ROW" / "QUICK MUTE" application path, to compare.
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraMute1.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute1 extends GhidraScript {
    DecompInterface dec;
    ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm;
    AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();

    String decomp(Function f) {
        try {
            DecompileResults dr = dec.decompileFunction(f, 120, mon);
            if (dr != null && dr.getDecompiledFunction() != null)
                return dr.getDecompiledFunction().getC();
        } catch (Exception e) {}
        return "  <decompile failed>";
    }

    void dumpFunc(Function f, String tag) {
        if (f == null) { println("  (no function -- " + tag + ")"); return; }
        if (!dumped.add(f.getEntryPoint().getOffset())) {
            println("\n// (already dumped " + f.getName() + " -- " + tag + ")");
            return;
        }
        println("\n########## " + f.getName() + " @" + f.getEntryPoint()
                + "  size=" + f.getBody().getNumAddresses() + "  (" + tag + ") ##########");
        println(decomp(f));
    }

    void xrefsTo(long addr, String label) {
        println("\n==================== xrefs to " + label + " @0x" + Long.toHexString(addr) + " ====================");
        Address a = sp.getAddress(addr);
        ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(a);
        int n = 0;
        List<Function> fns = new ArrayList<>();
        while (it.hasNext()) {
            Reference r = it.next();
            Address from = r.getFromAddress();
            Function cf = fm.getFunctionContaining(from);
            println("  from " + from + " in " + (cf != null ? cf.getName() + "@" + cf.getEntryPoint() : "NOFUNC")
                    + "  " + r.getReferenceType());
            if (cf != null) fns.add(cf);
            n++;
        }
        if (n == 0) println("  (none)");
        for (Function f : fns) dumpFunc(f, "xref of " + label);
    }

    // scan the whole image for instructions whose scalar operand equals `val`
    void scanImmediate(long val, String label) {
        println("\n==================== immediate/displacement scan for 0x" + Long.toHexString(val)
                + " (" + label + ") ====================");
        InstructionIterator ii = currentProgram.getListing().getInstructions(true);
        int hits = 0;
        while (ii.hasNext() && hits < 120) {
            Instruction ins = ii.next();
            String s = ins.toString();
            String hex = Long.toHexString(val);
            if (s.contains("0x" + hex) || s.contains(hex + "(") || s.contains("," + hex) || s.contains("#" + val)) {
                Function cf = fm.getFunctionContaining(ins.getAddress());
                println("  " + ins.getAddress() + "  " + s + "   [" + (cf != null ? cf.getName() : "?") + "]");
                hits++;
            }
        }
        if (hits == 0) println("  (none)");
    }

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec = new DecompInterface();
        dec.openProgram(currentProgram);

        // ---- 1. strings of interest (file offset + 0x40000400 = load addr) ----
        long BASE = 0x40000400L;
        long[] strOff = { 0xb3fadL, 0xb4f76L, 0xb5f17L, 0xb5f38L, 0xb428bL, 0xb4f8aL /*ARRANGEMENT EDITOR*/ };
        String[] strNm = { "MUTE ROW:%03d", "QUICK MUTE", "MUTE FOCUSES TRK", "CUE MUTES TRK", "ARRANGEMENT IS EMPTY", "ARR EDITOR-ish" };
        for (int i = 0; i < strOff.length; i++) {
            xrefsTo(BASE + strOff[i], "\"" + strNm[i] + "\"");
        }

        // ---- 2. voice-command primitives & voice state ----
        long[] fns = { 0x40005178L, 0x400977ccL, 0x40000ee0L, 0x4000c8a4L };
        String[] fnNm = { "FUN_40005178 voice-command(queue)", "FUN_400977cc trig->voice",
                          "FUN_40000ee0 active-voice query", "FUN_4000c8a4 frame builder" };
        for (int i = 0; i < fns.length; i++) {
            Function f = fm.getFunctionContaining(sp.getAddress(fns[i]));
            dumpFunc(f, fnNm[i]);
        }

        // ---- 3. who reads/writes the per-track voice state base 0x800049d8 ----
        scanImmediate(0x800049d8L, "voice state base");
        // voice command mailboxes
        scanImmediate(0x800018beL, "voice mailbox A");
        scanImmediate(0x800018deL, "voice mailbox B");

        dec.dispose();
        println("\n[GhidraMute1] done.");
    }
}
