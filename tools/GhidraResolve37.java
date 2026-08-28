// GhidraResolve37.java
// Session 5 (Claude Code / native mac_arm_64 Ghidra). Key correction from GhidraResolve36:
// FUN_4009a670 is purely a load-time bounds-CLAMP (called only by the deserializer
// FUN_4008cebc, the pattern-init FUN_4009abdc, and the bulk validator FUN_40025770) --
// NOT on the manual-trig path. Dead end for the bug mechanism, but it does give authoritative
// field ranges: +0x8e55 SCALE_MODE clamped [0,1] (binary at pattern level), +0x48fd TRIG_MODE
// clamped [0,2], +0x48fe DIRECT clamped [-1,0x10].
//
// The actually-important correction: the handoff-5 claim that SCALE_MODE (+0x8e55) is read
// NOWHERE in the manual-trig-key dispatch chain is WRONG. FUN_4009b5c8's decompile (from the
// GhidraResolve32 log) plainly contains:
//     puVar9 = &DAT_400e21e0 + iVar7 + 0x8e54;
//     if ((&DAT_400e21e0)[iVar7 + 0x8e55] != '\0')
//         puVar9 = &DAT_400e2231 + iVar8 + param_1 * 0x91a + iVar6;   // <- param_1*0x91a suspicious
//     (&DAT_8000663e)[param_1] = *puVar9;
// The whole-image operand scan (GhidraResolve35) missed it because +0x8e55 is reached as
// [regA + 0x8e54] + 1, i.e. register-relative -- exactly the scan blind spot the project has
// already been bitten by twice (DIRECT read, and the earlier +0x8e55 read in FUN_400a1eea).
//
// This script gets ground truth on how SCALE_MODE feeds the trig path:
//   1. Full raw disasm of FUN_4009b5c8 (0x4009b5c8) and FUN_4009f3a4 (0x4009f3a4) -- need the
//      real addressing for the 0x8e54/0x8e55 read and the DAT_8000663e[track] store, and to
//      check whether FUN_4009f3a4 has the equivalent seed or omits it (the suspected bug).
//   2. Fresh decompile of both at high effort for side-by-side comparison.
//   3. Whole-image reader/writer scan for DAT_8000663e (0x8000663e) and its neighbours
//      0x8000663e..0x80006650 -- what consumes the per-track byte that SCALE_MODE selects the
//      source of.
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"; export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve37.java

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

public class GhidraResolve37 extends GhidraScript {
    DecompInterface decomp;
    FunctionManager fm;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        decomp = new DecompInterface();
        decomp.openProgram(currentProgram);

        for (String a : new String[] { "0x4009b5c8", "0x4009f3a4" }) {
            rawDisasm(a);
        }
        for (String a : new String[] { "0x4009b5c8", "0x4009f3a4" }) {
            decompileAt(a);
        }

        // scan for DAT_8000663e and a small window of neighbours
        Set<Long> targets = new LinkedHashSet<>();
        for (long v = 0x8000663eL; v <= 0x80006658L; v++) targets.add(v);
        println("\n==================== operand scan for 0x8000663e..0x80006658 ====================");
        InstructionIterator it = currentProgram.getListing().getInstructions(true);
        while (it.hasNext()) {
            Instruction insn = it.next();
            for (int op = 0; op < insn.getNumOperands(); op++) {
                for (Object o : insn.getOpObjects(op)) {
                    Long v = null;
                    if (o instanceof Scalar) v = ((Scalar) o).getUnsignedValue();
                    if (o instanceof Address) v = ((Address) o).getOffset();
                    if (v != null && targets.contains(v)) {
                        Function f = fm.getFunctionContaining(insn.getAddress());
                        println(String.format("  %s  %-36s  %s  (0x%x)",
                            insn.getAddress(),
                            f != null ? f.getName() + "@" + f.getEntryPoint() : "NOFUNC",
                            insn.toString(), v));
                    }
                }
            }
        }

        decomp.dispose();
        println("\nDone.");
    }

    void decompileAt(String a) {
        Address addr = currentProgram.getAddressFactory().getAddress(a);
        Function f = fm.getFunctionContaining(addr);
        println("\n==================== decompile " + a + " ====================");
        if (f == null) { println("No function at " + a); return; }
        DecompileResults res = decomp.decompileFunction(f, 180, new ConsoleTaskMonitor());
        if (res != null && res.decompileCompleted()) println(res.getDecompiledFunction().getC());
        else println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
    }

    void rawDisasm(String a) {
        Address addr = currentProgram.getAddressFactory().getAddress(a);
        Function f = fm.getFunctionContaining(addr);
        println("\n==================== raw disasm " + a + " ====================");
        if (f == null) { println("No function at " + a); return; }
        Address end = f.getBody().getMaxAddress();
        println("Function: " + f.getName() + "  " + f.getEntryPoint() + " .. " + end);
        Instruction insn = getInstructionAt(f.getEntryPoint());
        while (insn != null && insn.getAddress().compareTo(end) <= 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(insn.getAddress()).append("  ").append(insn.toString());
            Address[] flows = insn.getFlows();
            if (flows != null && flows.length > 0) {
                sb.append("   -> ");
                for (Address fl : flows) sb.append(fl).append(" ");
            }
            println(sb.toString());
            insn = getInstructionAfter(insn.getAddress());
        }
    }
}
