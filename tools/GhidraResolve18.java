// GhidraResolve18.java
//
// FUN_400a1608's only caller is named "candidate_400a1f2e" -- NOT a default
// Ghidra FUN_xxxxxxxx name. That naming must have come from an earlier
// session (likely the abandoned "MIDI PLAYS FREE + SCALE PER TRACK"
// investigation), which used this same Ghidra project and apparently
// flagged this function as relevant to something. Worth investigating
// directly, since it may be a shortcut into exactly the neighborhood we
// need for the current (corrected) DIRECT+Free bug.
//
// This script:
//   1. Lists ALL symbols anywhere in the program whose name contains
//      "candidate" (case-insensitive) -- if there's a small set of these
//      from earlier work, that's a partial map already drawn for us.
//   2. Dumps any comments (plate/pre/post/eol/repeatable) attached to
//      candidate_400a1f2e's function and entry address -- comments often
//      explain WHY something was flagged as a candidate.
//   3. Decompiles candidate_400a1f2e in full (the MIDI-side per-track
//      step processor -- caller of FUN_400a1608).
//   4. Decompiles FUN_40097924 in full (the audio-side per-track step
//      processor -- caller of trig_to_voice / FUN_400977cc), for direct
//      side-by-side comparison with the MIDI side.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;

public class GhidraResolve18 extends GhidraScript {

    private static final long MIDI_SIDE_CALLER = 0x400a1f2eL; // candidate_400a1f2e
    private static final long AUDIO_SIDE_CALLER = 0x40097924L; // FUN_40097924

    @Override
    public void run() throws Exception {
        println("=========================================================");
        println("PART 1: All symbols containing 'candidate' anywhere in the project");
        println("=========================================================");
        listCandidateSymbols();

        println("");
        println("=========================================================");
        println("PART 2: Comments on candidate_400a1f2e");
        println("=========================================================");
        dumpComments(MIDI_SIDE_CALLER);

        println("");
        println("=========================================================");
        println("PART 3: Decompiling candidate_400a1f2e (MIDI-side per-track step processor)");
        println("=========================================================");
        decompile(MIDI_SIDE_CALLER);

        println("");
        println("=========================================================");
        println("PART 4: Decompiling FUN_40097924 (audio-side per-track step processor)");
        println("=========================================================");
        decompile(AUDIO_SIDE_CALLER);

        println("");
        println("=== GhidraResolve18 complete ===");
    }

    private void listCandidateSymbols() throws Exception {
        SymbolTable symTable = currentProgram.getSymbolTable();
        SymbolIterator it = symTable.getSymbolIterator();
        int count = 0;

        while (it.hasNext()) {
            Symbol s = it.next();
            String name = s.getName();
            if (name != null && name.toLowerCase().contains("candidate")) {
                println("  " + s.getAddress() + "  " + name
                    + "  (type: " + s.getSymbolType() + ", source: " + s.getSource() + ")");
                count++;
            }
        }

        println("");
        println("Total 'candidate' symbols found: " + count);
    }

    private void dumpComments(long addrLong) throws Exception {
        Address addr = toAddr(addrLong);
        String[] labels = {"PLATE", "PRE", "EOL", "POST", "REPEATABLE"};
        int[] types = {
            CodeUnit.PLATE_COMMENT,
            CodeUnit.PRE_COMMENT,
            CodeUnit.EOL_COMMENT,
            CodeUnit.POST_COMMENT,
            CodeUnit.REPEATABLE_COMMENT
        };

        boolean any = false;
        for (int i = 0; i < types.length; i++) {
            String c = currentProgram.getListing().getComment(types[i], addr);
            if (c != null && !c.isEmpty()) {
                println("  [" + labels[i] + "] " + c);
                any = true;
            }
        }
        if (!any) {
            println("  (no comments found at " + addr + ")");
        }
    }

    private void decompile(long addrLong) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        println("");
        if (f == null) {
            println("No function defined at " + addr + " -- skipping decompile.");
            return;
        }
        println("--- " + f.getName() + " @ " + addr
            + "  (body size: " + f.getBody().getNumAddresses() + " bytes)");

        DecompInterface decomp = new DecompInterface();
        try {
            decomp.openProgram(currentProgram);
            DecompileResults res = decomp.decompileFunction(f, 90, monitor);

            if (res != null && res.decompileCompleted()) {
                println("Parameter count: " + f.getParameterCount());
                for (Parameter p : f.getParameters()) {
                    println("  param: " + p.getName() + " : " + p.getDataType());
                }
                println(res.getDecompiledFunction().getC());
            } else {
                String msg = (res != null) ? res.getErrorMessage() : "null DecompileResults";
                println("Decompilation FAILED or did not complete: " + msg);
            }
        } finally {
            decomp.dispose();
        }
    }
}
