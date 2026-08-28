// GhidraResolve30.java
// Save as tools/GhidraResolve30.java.
//
// FUN_4009f3a4 (session 4 part 3) is the strongest bug candidate yet: gates on PLAYS_FREE
// for MIDI tracks (param_1 8-15), then does `if (cVar1 == -1 [DIRECT] || _DAT_800065b8 != 1)
// { clear MIDI track state + FUN_400a539c(track) + post event } else { set/clear bits in
// _DAT_80006680/_DAT_80006682 }` -- the same bitmask pair FUN_400a1eea's per-step quantize
// engine already reads. Need: (1) every caller of FUN_4009f3a4 (who calls it, and with what
// argument if statically knowable) to learn when this actually runs -- manual key press,
// per-step, pattern-load, track-stop..., (2) a look at FUN_400a539c, the function it calls
// in the reset branch.
//
// Run headless (same pattern as GhidraResolve26-29):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve30.java

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

public class GhidraResolve30 extends GhidraScript {
    public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();

        Address targetAddr = currentProgram.getAddressFactory().getAddress("0x4009f3a4");
        println("==== Callers of FUN_4009f3a4 (0x4009f3a4) ====");
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(targetAddr);
        while (refs.hasNext()) {
            Reference r = refs.next();
            Address from = r.getFromAddress();
            Function callerFunc = fm.getFunctionContaining(from);
            Instruction insn = getInstructionAt(from);
            println("  from " + from + " in " + (callerFunc != null ? callerFunc.getName() + "@" + callerFunc.getEntryPoint() : "NOFUNC")
                + "  refType=" + r.getReferenceType()
                + "  insn=" + (insn != null ? insn.toString() : "?"));
            // print a little context around the call site
            if (insn != null) {
                Address cur = from;
                for (int i = 0; i < 12; i++) {
                    Instruction prev = getInstructionBefore(cur);
                    if (prev == null) break;
                    cur = prev.getAddress();
                }
                for (int i = 0; i < 20; i++) {
                    Instruction cx = getInstructionAt(cur);
                    if (cx == null) break;
                    String marker = cx.getAddress().equals(from) ? " >>> " : "     ";
                    println(marker + cx.getAddress() + "  " + cx.toString());
                    if (cx.getAddress().equals(from)) {
                        // print a couple after too
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

        println("\n==== FUN_400a539c decompile ====");
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        Address a2 = currentProgram.getAddressFactory().getAddress("0x400a539c");
        Function f2 = fm.getFunctionContaining(a2);
        if (f2 == null) {
            try { disassemble(a2); f2 = createFunction(a2, "candidate_400a539c"); } catch (Exception e) { println("createFunction failed: " + e); }
        }
        if (f2 != null) {
            println("Function: " + f2.getName() + " Entry:" + f2.getEntryPoint() + " Size:" + f2.getBody().getNumAddresses());
            DecompileResults res = decomp.decompileFunction(f2, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
            }
        } else {
            println("No function at 0x400a539c");
        }
        decomp.dispose();
        println("\nDone.");
    }
}
