// GhidraResolve32.java
// Save as tools/GhidraResolve32.java.
//
// User clarified: (1) a Plays-Free MIDI track manually triggered should start running even
// when the OT's overall sequencer transport is stopped; (2) the 3-valued byte found this
// session at blob +0x48fd is NOT "scale mode" -- it's the manual-trig-key RESPONSE MODE
// per Elektron's own UI terms: "ONE" (retrigger every press -- our test data's value),
// "ONE2" (toggle: press starts, press again stops), "HOLD" (plays only while held). Need
// to: (a) find every WRITE to _DAT_800065b8 (the flag OR'd with DIRECT to gate
// FUN_4009f3a4's clear-vs-bitflip branch) to see if it reflects overall transport
// play/stop state, not just "MIDI pattern loaded" as guessed in session 3; (b) decompile
// FUN_4009b5c8, the "normal start" path FUN_40044584 falls through to, to see whether it
// can start a track independent of transport state (tests the user's point directly);
// (c) same write-search for _DAT_80000012 (the outer gate in FUN_40044584) for context.
//
// Run headless (same pattern as GhidraResolve26-31):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve32.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.address.Address;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraResolve32 extends GhidraScript {

    void findWrites(long targetAddr, String label) {
        println("\n==== Writes to " + label + " (0x" + Long.toHexString(targetAddr) + ") ====");
        FunctionManager fm = currentProgram.getFunctionManager();
        InstructionIterator it = currentProgram.getListing().getInstructions(true);
        while (it.hasNext()) {
            Instruction insn = it.next();
            String mn = insn.getMnemonicString().toLowerCase();
            // heuristics for m68k/coldfire "write" mnemonics: move/movea/clr/st/etc where
            // operand 1 (destination, last operand) is the target address, or move.b Dn,(target).l
            boolean isMoveLike = mn.startsWith("move") || mn.equals("clr") || mn.equals("st") || mn.equals("sf");
            if (!isMoveLike) continue;
            int numOps = insn.getNumOperands();
            if (numOps == 0) continue;
            int destOp = numOps - 1; // destination is typically the last operand in Ghidra's rendering
            Object[] objs = insn.getOpObjects(destOp);
            for (Object o : objs) {
                Long v = null;
                if (o instanceof Scalar) v = ((Scalar) o).getUnsignedValue();
                if (o instanceof Address) v = ((Address) o).getOffset();
                if (v != null && v == targetAddr) {
                    Function f = fm.getFunctionContaining(insn.getAddress());
                    println("  " + insn.getAddress() + "  " + insn.toString() + "   in " +
                        (f != null ? f.getName() + "@" + f.getEntryPoint() : "NOFUNC"));
                }
            }
        }
    }

    public void run() throws Exception {
        findWrites(0x800065b8L, "DAT_800065b8 (MIDI-pattern-loaded / gate flag)");
        findWrites(0x80000012L, "DAT_80000012 (outer MIDI-mode gate in FUN_40044584)");

        println("\n==== FUN_4009b5c8 decompile ====");
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        Address a = currentProgram.getAddressFactory().getAddress("0x4009b5c8");
        Function f = fm.getFunctionContaining(a);
        if (f == null) {
            try { disassemble(a); f = createFunction(a, "candidate_4009b5c8"); } catch (Exception e) { println("createFunction failed: " + e); }
        }
        if (f != null) {
            println("Function: " + f.getName() + " Entry:" + f.getEntryPoint() + " Size:" + f.getBody().getNumAddresses());
            DecompileResults res = decomp.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
            }
        } else {
            println("No function at 0x4009b5c8");
        }
        decomp.dispose();
        println("\nDone.");
    }
}
