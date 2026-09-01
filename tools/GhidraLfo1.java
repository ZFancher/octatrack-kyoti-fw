// GhidraLfo1.java -- NEW BUG (2026-08): MIDI-track LFO SETUP page SPD/DEP knobs
// transmit CC 28-33 on the *twin audio track's* MIDI channel. Goal of this pass:
//   1. xrefs to UI strings that anchor the LFO SETUP page:
//        "MIDI LFO SETUP" @0x400b47d5, "SPD1" clusters @0x400d380c / 0x400d4178
//   2. xrefs to the CC-out gate strings: "AUDIO CC OUT" @0x400b623d, "AUDIO CC IN"
//        @0x400b6231, "CC DIRECT CONNECT" @0x400b5fc0, "INT+EXT" @0x400b61ff
//   3. callers of the MIDI-send primitive FUN_40010bc8 (from session 5) -- which
//        ones build a CC (0xB0) message, and how do they pick the channel?
//   4. any code referencing immediates 0x1c..0x21 (CC 28..33) as a base.
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraLfo1.java
//@category Octatrack

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo1 extends GhidraScript {
    FunctionManager fm;
    ReferenceManager rm;

    void xrefs(String label, long va) {
        println("\n==================== xrefs to " + label + " @0x" + Long.toHexString(va) + " ====================");
        Address a = toAddr(va);
        int n = 0;
        for (Reference r : rm.getReferencesTo(a)) {
            Address from = r.getFromAddress();
            Function cf = fm.getFunctionContaining(from);
            Instruction ix = getInstructionAt(from);
            Data dx = getDataAt(from);
            println("  from " + from + "  " + r.getReferenceType()
                + "  in " + (cf != null ? cf.getName() + "@" + cf.getEntryPoint() : "NOFUNC")
                + (ix != null ? "   [" + ix + "]" : dx != null ? "   {data " + dx + "}" : ""));
            n++;
        }
        if (n == 0) println("  (none -- maybe not a direct-referenced string; try scanning pointers)");
    }

    void callers(long va, String name) {
        println("\n==================== callers of " + name + " @0x" + Long.toHexString(va) + " ====================");
        Address t = toAddr(va);
        for (Reference r : rm.getReferencesTo(t)) {
            Address from = r.getFromAddress();
            Function cf = fm.getFunctionContaining(from);
            println("  from " + from + "  " + r.getReferenceType()
                + "  in " + (cf != null ? cf.getName() + "@" + cf.getEntryPoint() : "NOFUNC"));
            // print ~14 insns of lead-in context
            Address cur = from;
            ArrayList<Instruction> lead = new ArrayList<>();
            for (int i = 0; i < 14; i++) { Instruction p = getInstructionBefore(cur); if (p == null) break; cur = p.getAddress(); lead.add(p); }
            Collections.reverse(lead);
            for (Instruction p : lead) println("        " + p.getAddress() + "  " + p);
            println("    >>> " + from + "  " + getInstructionAt(from));
            Instruction nx = getInstructionAfter(from);
            for (int i = 0; i < 4 && nx != null; i++) { println("        " + nx.getAddress() + "  " + nx); nx = getInstructionAfter(nx.getAddress()); }
            println("");
        }
    }

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        rm = currentProgram.getReferenceManager();

        xrefs("\"MIDI LFO SETUP\"", 0x400b47d5L);
        xrefs("\"SPD1\" cluster A", 0x400d380cL);
        xrefs("\"SPD1\" cluster B", 0x400d4178L);
        xrefs("\"AUDIO CC OUT\"", 0x400b623dL);
        xrefs("\"AUDIO CC IN\"", 0x400b6231L);
        xrefs("\"CC DIRECT CONNECT\"", 0x400b5fc0L);
        xrefs("\"INT+EXT\"", 0x400b61ffL);

        callers(0x40010bc8L, "FUN_40010bc8 (MIDI-send primitive?)");

        println("\n==================== scan for CC-base immediates 0x1c..0x21 ====================");
        Listing lst = currentProgram.getListing();
        InstructionIterator it = lst.getInstructions(true);
        int hits = 0;
        while (it.hasNext() && hits < 400) {
            Instruction ins = it.next();
            String s = ins.toString();
            if (s.matches(".*#0x1[cdef],.*") || s.matches(".*#0x2[01],.*")) {
                Function cf = fm.getFunctionContaining(ins.getAddress());
                // only report if near an LFO/CC-ish function name or a jsr to the send primitive nearby -- too noisy otherwise;
                // just tally + print first 60
                if (hits < 60) println("  " + ins.getAddress() + "  " + s + "   in " + (cf != null ? cf.getName() : "?"));
                hits++;
            }
        }
        println("  ... total immediate hits (capped): " + hits);
        println("\nDone.");
    }
}
