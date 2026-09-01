// GhidraResolve29.java
// Save as tools/GhidraResolve29.java.
//
// Session 4 continued (part 3): whole-image operand scan (GhidraResolve28) found
// FUN_4009f3a4 reads PLAYS_FREE (+0x48fc) and DIRECT (+0x48fe) directly -- not through the
// sequencer's per-step loop like FUN_400a1eea, its own separate logic. It sits inside the
// 0x4009be00-0x4009f650 region session 3 part 4 already flagged as hosting a not-yet-named
// function tied to MIDI track state (0x80006508/0x80006646), found then via xrefs to those
// arrays but never pinned down/decompiled. Also several undefined-boundary hits nearby
// (0x4009b616, 0x4009bd5e, 0x4009c490, 0x4009f5c8) that reference PLAYS_FREE/DIRECT but
// have no properly analyzed function covering them.
//
// Decompile FUN_4009f3a4 in full, and try to create+decompile functions at the undefined
// addresses (same disassemble+createFunction fallback pattern as the project's existing
// tools/ghidra_decompile.py) so we're not just looking at raw disassembly for those.
//
// Run headless (same pattern as GhidraResolve26-28):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve29.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve29 extends GhidraScript {
    public void run() throws Exception {
        String[] addrs = new String[] {
            "0x4009f3a4",  // reads PLAYS_FREE + DIRECT directly, in the flagged region
            "0x4009b616",  // undefined-boundary hit: tst.b (0x48fc,A0)
            "0x4009bd5e",  // undefined-boundary hit: addi.l #0x48fc,D0
            "0x4009c490",  // undefined-boundary hit: addi.l #0x48fc,D0
            "0x4009f5c8",  // "candidate" function, addi.l #0x48fc,D0
        };

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();

        for (String a : addrs) {
            Address addr = currentProgram.getAddressFactory().getAddress(a);
            Function f = fm.getFunctionContaining(addr);

            println("\n==================== " + a + " ====================");
            if (f == null) {
                println("No function contains this address. Trying disassemble+createFunction...");
                try {
                    disassemble(addr);
                    f = createFunction(addr, "candidate_" + a.replace("0x",""));
                } catch (Exception e) {
                    println("  createFunction failed: " + e);
                }
            }
            if (f == null) {
                println("Still no function here -- skipping decompile, dumping raw instructions instead.");
                Address cur = addr;
                for (int i = 0; i < 40; i++) {
                    ghidra.program.model.listing.Instruction insn = getInstructionAt(cur);
                    if (insn == null) { insn = disassemble(cur) ? getInstructionAt(cur) : null; }
                    if (insn == null) break;
                    println("  " + insn.getAddress() + "  " + insn.toString());
                    cur = insn.getAddress().add(insn.getLength());
                }
                continue;
            }
            println("Function: " + f.getName() + "  Entry: " + f.getEntryPoint()
                + "  Body size: " + f.getBody().getNumAddresses() + " bytes");

            DecompileResults res = decomp.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompilation failed: " + (res != null ? res.getErrorMessage() : "null result"));
            }
        }

        decomp.dispose();
        println("\nDone.");
    }
}
