// GhidraResolve17.java
//
// Repo docs (ARCHITECTURE.md, NOTES.md, COVERAGE.md) confirm two real,
// named functions relevant to our bug:
//
//   FUN_400977cc  - "Trig -> voice" dispatcher, described purely in AUDIO
//                   terms (dispatches by machine type 0-4, a concept that
//                   doesn't apply to MIDI tracks). Very likely audio-only.
//
//   FUN_400a1608  - caller of the confirmed MIDI note-trig emitter
//                   FUN_4009f794. Our best lead for the MIDI-side trig path.
//
// COVERAGE.md explicitly states the MIDI sequencer engine was never mapped
// by the original repo authors, so this is genuinely new ground.
//
// This script:
//   1. Decompiles both functions in full.
//   2. Dumps callers (xrefs) of each -- looking for a shared ancestor
//      function where the sequencer decides "this track is audio" vs
//      "this track is MIDI" and branches accordingly. That fork point
//      is likely where our bug's real context lives, or at least the
//      map to get there.
//   3. Also dumps callers of FUN_4009f794 (the MIDI note-trig emitter
//      itself) for completeness, since it's the most concretely-verified
//      MIDI-side function we have.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraResolve17 extends GhidraScript {

    private static final long AUDIO_TRIG_DISPATCH = 0x400977ccL;
    private static final long MIDI_TRIG_CALLER = 0x400a1608L;
    private static final long MIDI_QUANTIZER = 0x4009f794L;

    @Override
    public void run() throws Exception {
        println("=========================================================");
        println("PART 1: FUN_400977cc (confirmed audio trig->voice dispatcher)");
        println("=========================================================");
        decompile(AUDIO_TRIG_DISPATCH);
        dumpCallers(AUDIO_TRIG_DISPATCH, "FUN_400977cc");

        println("");
        println("=========================================================");
        println("PART 2: FUN_400a1608 (caller of MIDI note-trig emitter)");
        println("=========================================================");
        decompile(MIDI_TRIG_CALLER);
        dumpCallers(MIDI_TRIG_CALLER, "FUN_400a1608");

        println("");
        println("=========================================================");
        println("PART 3: Callers of FUN_4009f794 (MIDI quantizer / note-trig emitter)");
        println("=========================================================");
        dumpCallers(MIDI_QUANTIZER, "FUN_4009f794");

        println("");
        println("=== GhidraResolve17 complete ===");
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

    private void dumpCallers(long addrLong, String label) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        println("");
        println("--- Callers (xrefs) of " + label + " @ " + addr + " ---");

        ReferenceIterator refIter = refMgr.getReferencesTo(addr);
        int count = 0;
        while (refIter.hasNext()) {
            Reference r = refIter.next();
            Address from = r.getFromAddress();
            Function containing = fm.getFunctionContaining(from);
            String containingName = (containing != null) ? containing.getName() : "(no containing function)";
            println("  from " + from + "  type=" + r.getReferenceType() + "  in function: " + containingName);
            count++;
        }

        if (count == 0) {
            println("  No references found -- likely reached via a function-pointer table or "
                + "computed call, same pattern we've seen before with menu dispatch tables.");
        } else {
            println("  Found " + count + " reference(s) total (listed above).");
        }
    }
}
