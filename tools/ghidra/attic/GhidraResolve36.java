// GhidraResolve36.java
// Session 5 (Claude Code, on the user's Mac -- native mac_arm_64 Ghidra 12.1.2, no from-source
// build needed). Handoff-5 Open Item 1: decompile FUN_4009a670 -- the only non-deserializer,
// non-step-engine function that references all three of SCALE_MODE (+0x8e55), the pattern
// fallback quantlen (+0x8e53), and the per-track quantlen (+0x48f8), and it sits right next
// to the manual-trig-key handlers (FUN_4009b290 / FUN_4009b5c8 / FUN_4009f3a4). Goal: find
// the mechanism by which pattern-level SCALE_MODE gates the bug, since it is confirmed
// ABSENT from the FUN_40044584 -> FUN_4009b5c8 / FUN_4009f3a4 dispatch chain.
//
// GhidraResolve35 (re-run this session) reconfirmed the reader set for {0x8e55,0x8e53,0x48f8}:
//   FUN_4008a6fc, FUN_4008cebc (known deserializer), FUN_4009a670, FUN_400a1eea (step engine).
// FUN_4009abdc (immediately after FUN_4009a670) also touches +0x48f8.
//
// This script:
//   1. Full decompile of FUN_4009a670, FUN_4009abdc, FUN_4008a6fc.
//   2. Callers (with call-site context) of FUN_4009a670 and FUN_4009abdc.
//   3. Full raw disassembly of FUN_4009a670 (decompiler-misrender lesson: verify branches).
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve36.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve36 extends GhidraScript {
    DecompInterface decomp;
    FunctionManager fm;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        decomp = new DecompInterface();
        decomp.openProgram(currentProgram);

        for (String a : new String[] { "0x4009a670", "0x4009abdc", "0x4008a6fc" }) {
            decompileAt(a);
        }

        callers("0x4009a670");
        callers("0x4009abdc");

        rawDisasm("0x4009a670");

        decomp.dispose();
        println("\nDone.");
    }

    void decompileAt(String a) {
        Address addr = currentProgram.getAddressFactory().getAddress(a);
        Function f = fm.getFunctionContaining(addr);
        println("\n==================== decompile " + a + " ====================");
        if (f == null) {
            try { disassemble(addr); f = createFunction(addr, null); } catch (Exception e) { println("createFunction failed: " + e); }
        }
        if (f == null) { println("No function at " + a); return; }
        println("Function: " + f.getName() + " Entry:" + f.getEntryPoint()
            + " Size:" + f.getBody().getNumAddresses());
        DecompileResults res = decomp.decompileFunction(f, 120, new ConsoleTaskMonitor());
        if (res != null && res.decompileCompleted()) {
            println(res.getDecompiledFunction().getC());
        } else {
            println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
        }
    }

    void callers(String a) {
        Address targetAddr = currentProgram.getAddressFactory().getAddress(a);
        println("\n==================== callers of " + a + " ====================");
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(targetAddr);
        boolean any = false;
        while (refs.hasNext()) {
            any = true;
            Reference r = refs.next();
            Address from = r.getFromAddress();
            Function cf = fm.getFunctionContaining(from);
            Instruction insn = getInstructionAt(from);
            println("  from " + from + " in "
                + (cf != null ? cf.getName() + "@" + cf.getEntryPoint() : "NOFUNC")
                + "  refType=" + r.getReferenceType()
                + "  insn=" + (insn != null ? insn.toString() : "?"));
            if (insn != null) {
                Address cur = from;
                for (int i = 0; i < 14; i++) {
                    Instruction prev = getInstructionBefore(cur);
                    if (prev == null) break;
                    cur = prev.getAddress();
                }
                for (int i = 0; i < 40; i++) {
                    Instruction cx = getInstructionAt(cur);
                    if (cx == null) break;
                    String marker = cx.getAddress().equals(from) ? " >>> " : "     ";
                    println(marker + cx.getAddress() + "  " + cx.toString());
                    if (cx.getAddress().equals(from)) {
                        Instruction nx = cx;
                        for (int k = 0; k < 4; k++) {
                            nx = getInstructionAfter(nx.getAddress());
                            if (nx == null) break;
                            println("     " + nx.getAddress() + "  " + nx.toString());
                        }
                        break;
                    }
                    Instruction nxt = getInstructionAfter(cx.getAddress());
                    if (nxt == null) break;
                    cur = nxt.getAddress();
                }
            }
            println("");
        }
        if (!any) println("  (no references)");
    }

    void rawDisasm(String a) {
        Address addr = currentProgram.getAddressFactory().getAddress(a);
        Function f = fm.getFunctionContaining(addr);
        println("\n==================== raw disasm " + a + " ====================");
        if (f == null) { println("No function at " + a); return; }
        Address start = f.getEntryPoint();
        Address end = f.getBody().getMaxAddress();
        println("Function: " + f.getName() + "  " + start + " .. " + end);
        Instruction insn = getInstructionAt(start);
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
