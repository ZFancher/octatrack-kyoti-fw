// GhidraResolve38.java
// Session 5. Established so far this session:
//  * FUN_4009a670 = load-time clamp only (dead end for bug mechanism); gives field ranges:
//      +0x8e55 SCALE_MODE [0,1], +0x48fd TRIG_MODE [0,2], +0x48fe DIRECT [-1,0x10].
//  * FUN_4009b5c8's full-init branch DOES read pattern-level SCALE_MODE (+0x8e55) -- the
//    handoff-5 "not read anywhere in the trig chain" claim is wrong. It uses it to pick the
//    SOURCE of the byte stored into DAT_8000663e[track]:
//       SCALE_MODE==0 -> pattern byte at blob+0x8e54
//       SCALE_MODE!=0 -> per-track byte (decompiler renders src as &DAT_400e2231 + pat*0x8ed8
//                        + param_1*0x91a + bank -- the 0x91a (AUDIO stride) on a MIDI track
//                        index is suspicious; likely a register-reuse misrender -> need raw asm)
//  * FUN_4009f3a4's clear branch does NOT touch DAT_8000663e and never reactivates. Bug
//    mechanism confirmed unchanged.
//  * DAT_8000663e[track] is READ only by FUN_400a1eea (step engine), 3 sites:
//    0x400a292a, 0x400a2970, 0x400a3cb4.
//
// So the "why does per-track scale mode matter to the bug" answer must be: SCALE_MODE changes
// the VALUE in DAT_8000663e[track], which changes how FUN_400a1eea's step engine treats the
// track -- plausibly whether the step engine re-triggers / papers over the missing
// reactivation. This script gets the ground truth:
//   1. Raw disasm range 0x4009b64c .. 0x4009b95c  (FUN_4009b5c8 full-init tail incl. the
//      0x8e54/0x8e55 read and the DAT_8000663e store -- resolve the misrendered source addr).
//   2. Raw disasm range 0x4009f474 .. 0x4009f5c0  (FUN_4009f3a4 tail, for completeness).
//   3. Raw disasm +-40 insns around each of the 3 FUN_400a1eea reads of DAT_8000663e.
//   4. Decompile FUN_400a1eea in full (large, but we need the 3 read contexts in C).
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"; export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve38.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve38 extends GhidraScript {
    FunctionManager fm;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();

        range("FUN_4009b5c8 full-init tail", "0x4009b64c", "0x4009b95c");
        range("FUN_4009f3a4 tail",           "0x4009f474", "0x4009f5c0");

        for (String a : new String[] { "0x400a292a", "0x400a2970", "0x400a3cb4" }) {
            around("FUN_400a1eea DAT_8000663e read @ " + a, a, 42, 20);
        }

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        Address da = currentProgram.getAddressFactory().getAddress("0x400a1eea");
        Function df = fm.getFunctionContaining(da);
        println("\n==================== decompile FUN_400a1eea ====================");
        if (df != null) {
            DecompileResults res = decomp.decompileFunction(df, 240, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) println(res.getDecompiledFunction().getC());
            else println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
        } else println("no function at 0x400a1eea");
        decomp.dispose();

        println("\nDone.");
    }

    void printInsn(Instruction insn) {
        StringBuilder sb = new StringBuilder();
        sb.append(insn.getAddress()).append("  ").append(insn.toString());
        Address[] flows = insn.getFlows();
        if (flows != null && flows.length > 0) {
            sb.append("   -> ");
            for (Address fl : flows) sb.append(fl).append(" ");
        }
        println(sb.toString());
    }

    void range(String label, String startS, String endS) {
        Address start = currentProgram.getAddressFactory().getAddress(startS);
        Address end = currentProgram.getAddressFactory().getAddress(endS);
        println("\n==================== raw disasm [" + startS + " .. " + endS + "]  " + label + " ====================");
        Instruction insn = getInstructionAt(start);
        if (insn == null) insn = getInstructionAfter(start);
        while (insn != null && insn.getAddress().compareTo(end) <= 0) {
            printInsn(insn);
            insn = getInstructionAfter(insn.getAddress());
        }
    }

    void around(String label, String atS, int before, int after) {
        Address at = currentProgram.getAddressFactory().getAddress(atS);
        println("\n==================== " + label + " ====================");
        Address cur = at;
        for (int i = 0; i < before; i++) {
            Instruction p = getInstructionBefore(cur);
            if (p == null) break;
            cur = p.getAddress();
        }
        int limit = before + after + 4;
        for (int i = 0; i < limit; i++) {
            Instruction cx = getInstructionAt(cur);
            if (cx == null) break;
            String mark = cx.getAddress().equals(at) ? " >>> " : "     ";
            StringBuilder sb = new StringBuilder(mark);
            sb.append(cx.getAddress()).append("  ").append(cx.toString());
            Address[] flows = cx.getFlows();
            if (flows != null && flows.length > 0) { sb.append("   -> "); for (Address fl : flows) sb.append(fl).append(" "); }
            println(sb.toString());
            Instruction nx = getInstructionAfter(cur);
            if (nx == null) break;
            cur = nx.getAddress();
        }
    }
}
