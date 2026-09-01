// GhidraResolve27.java
// Save as tools/GhidraResolve27.java.
//
// Session 4 continued: byte-diffing a scale-mode test pair found two new fields:
//   blob +0x48fd (file 0x4964) - per-track SCALE_MODE, sits between PLAYS_FREE (+0x48fc)
//                                and DIRECT/TRIGQUANT (+0x48fe) in the already-known
//                                tight per-track MIDI-trig header.
//   blob +0x8e55 (per-pattern area, near the pattern block's "PTRN"/"TRAC" tags) - flips
//                                together with the per-track byte above. This exact
//                                literal (0x8e55) already appears in FUN_400a1eea's
//                                decompiled C from GhidraResolve26, inside a branch gated
//                                on track-index==0, rendered by the decompiler as
//                                puVar45[CONCAT22(cVar11 >> 7, 0x8e55)] == 0 -- a pattern
//                                that usually means "decompiler linearized a sign-based
//                                address select"; want the RAW instructions to check.
//
// This script disassembles FUN_400a1eea and prints every instruction whose operands
// reference any of: 0x48fc, 0x48fd, 0x48fe, 0x8e52, 0x8e53, 0x8e54, 0x8e55, 0x48f8, 0x48f9,
// with 8 instructions of context before/after each hit -- so we can read the real asm
// around the CONCAT22 branch instead of trusting the decompiler's algebraic rendering.
//
// Run headless (same invocation pattern as GhidraResolve26):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve27.java

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.address.Address;
import ghidra.program.model.scalar.Scalar;
import java.util.*;

public class GhidraResolve27 extends GhidraScript {
    public void run() throws Exception {
        long[] targets = new long[] {
            0x48fc, 0x48fd, 0x48fe, 0x8e52, 0x8e53, 0x8e54, 0x8e55, 0x48f8, 0x48f9
        };
        Set<Long> targetSet = new HashSet<>();
        for (long t : targets) targetSet.add(t);

        Address funcAddr = currentProgram.getAddressFactory().getAddress("0x400a1eea");
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(funcAddr);
        if (f == null) { println("Function not found at 0x400a1eea"); return; }

        List<Instruction> all = new ArrayList<>();
        InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
        while (it.hasNext()) all.add(it.next());

        println("Total instructions in FUN_400a1eea: " + all.size());

        for (int i = 0; i < all.size(); i++) {
            Instruction insn = all.get(i);
            boolean hit = false;
            int numOps = insn.getNumOperands();
            for (int op = 0; op < numOps; op++) {
                Object[] objs = insn.getOpObjects(op);
                for (Object o : objs) {
                    if (o instanceof Scalar) {
                        long v = ((Scalar) o).getUnsignedValue();
                        if (targetSet.contains(v)) { hit = true; }
                    }
                    if (o instanceof Address) {
                        long v = ((Address) o).getOffset();
                        if (targetSet.contains(v)) { hit = true; }
                    }
                }
            }
            if (hit) {
                println("\n---- HIT at " + insn.getAddress() + " : " + insn.toString() + " ----");
                int lo = Math.max(0, i - 8);
                int hi = Math.min(all.size(), i + 9);
                for (int j = lo; j < hi; j++) {
                    String marker = (j == i) ? " >>> " : "     ";
                    println(marker + all.get(j).getAddress() + "  " + all.get(j).toString());
                }
            }
        }
        println("\nDone.");
    }
}
