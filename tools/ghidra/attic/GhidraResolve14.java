// GhidraResolve14.java
//
// Run AFTER a full re-analysis pass (drop -noanalysis) now that the bad
// stub at 0x4006e810 has been removed and FUN_4006e450 is a properly
// bounded function again -- Decompiler Switch Analysis may now be able to
// resolve indirect/switch-table call sites it couldn't before.
//
// This script does three things:
//   1. Dumps all cross-references TO our three functions of interest:
//        FUN_4006e450        (menu-dispatch function, corrected boundary)
//        direct_ref_40081f64 (DIRECT label getter #1)
//        direct_ref_400826d4 (DIRECT label getter #2)
//      If real analysis now resolves the jump table, we expect to finally
//      see call sites here that raw byte-pattern search couldn't find.
//
//   2. Dumps references (read/write, where Ghidra can tell) to the two
//      context globals seen feeding FUN_4006e450:
//        DAT_46c8d1a0
//        DAT_46c8d19c
//      Goal: find where these get WRITTEN, to determine whether they're a
//      menu/screen-context ID, a track index, or something else -- this
//      resolves the addressing-model ambiguity flagged after GhidraResolve13.
//
//   3. Scans the ENTIRE program for instructions with a scalar/displacement
//      operand equal to 0x129 (297) -- the byte offset that held the
//      trig-mode field (negative = DIRECT, 0-16 = quantize-length index)
//      inside FUN_4006e450. Any other function touching offset +0x129 off
//      a similar base pointer is a strong candidate for the real trig-mode
//      read/write, including the actual step-advance code we're hunting.
//
// All output goes through println() so it lands in the headless log.

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.SymbolTable;

public class GhidraResolve14 extends GhidraScript {

    // Functions of interest, by entry address.
    private static final long FUNC_MENU_DISPATCH = 0x4006e450L;
    private static final long FUNC_DIRECT_REF_1 = 0x40081f64L;
    private static final long FUNC_DIRECT_REF_2 = 0x400826d4L;

    // Context globals seen driving FUN_4006e450's dispatch.
    private static final long DAT_CONTEXT_1 = 0x46c8d1a0L; // _DAT_46c8d1a0
    private static final long DAT_CONTEXT_2 = 0x46c8d19cL; // _DAT_46c8d19c

    // The struct offset we found holding the trig-mode byte.
    private static final long TARGET_OFFSET = 0x129L;

    @Override
    public void run() throws Exception {
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        println("=========================================================");
        println("PART 1: Cross-references to functions of interest");
        println("=========================================================");
        dumpXrefsTo("FUN_4006e450 (menu-dispatch, corrected boundary)", FUNC_MENU_DISPATCH, refMgr);
        dumpXrefsTo("direct_ref_40081f64 (DIRECT label getter #1)", FUNC_DIRECT_REF_1, refMgr);
        dumpXrefsTo("direct_ref_400826d4 (DIRECT label getter #2)", FUNC_DIRECT_REF_2, refMgr);

        println("");
        println("=========================================================");
        println("PART 2: References to context globals DAT_46c8d1a0 / DAT_46c8d19c");
        println("=========================================================");
        dumpXrefsTo("DAT_46c8d1a0", DAT_CONTEXT_1, refMgr);
        dumpXrefsTo("DAT_46c8d19c", DAT_CONTEXT_2, refMgr);

        println("");
        println("=========================================================");
        println("PART 3: Program-wide scan for scalar/displacement operand == 0x129");
        println("=========================================================");
        scanForOffset(TARGET_OFFSET);

        println("");
        println("=== GhidraResolve14 complete ===");
    }

    private void dumpXrefsTo(String label, long addrLong, ReferenceManager refMgr) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        println("");
        println("--- " + label + " @ " + addr + (f != null ? " (function: " + f.getName() + ")" : " (no function defined here)"));

        ReferenceIterator refIter = refMgr.getReferencesTo(addr);
        int count = 0;
        while (refIter.hasNext()) {
            Reference r = refIter.next();
            Address from = r.getFromAddress();
            Function containing = fm.getFunctionContaining(from);
            String containingName = (containing != null) ? containing.getName() : "(no containing function)";
            println("    from " + from + "  type=" + r.getReferenceType()
                + "  in function: " + containingName);
            count++;
        }

        if (count == 0) {
            println("  No references found (formal xrefs). If this is unexpected for a "
                + "function we know is used, it's likely still reached via an unresolved "
                + "jump table or computed call, not a direct bsr/jsr.");
        } else {
            println("  Found " + count + " reference(s) total (listed above).");
        }
    }

    private void scanForOffset(long targetOffset) throws Exception {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        InstructionIterator it = listing.getInstructions(true);

        int hitCount = 0;
        int scanned = 0;

        while (it.hasNext() && !monitor.isCancelled()) {
            Instruction insn = it.next();
            scanned++;

            int numOps = insn.getNumOperands();
            for (int opIndex = 0; opIndex < numOps; opIndex++) {
                Object[] opObjs = insn.getOpObjects(opIndex);
                for (Object obj : opObjs) {
                    if (obj instanceof Scalar) {
                        Scalar s = (Scalar) obj;
                        long val = s.getSignedValue();
                        long uval = s.getUnsignedValue();
                        if (val == targetOffset || uval == targetOffset) {
                            Address at = insn.getAddress();
                            Function containing = fm.getFunctionContaining(at);
                            String containingName = (containing != null) ? containing.getName() : "(no containing function)";
                            println("  HIT: " + at + "  \"" + insn.toString() + "\"  in function: " + containingName);
                            hitCount++;
                        }
                    }
                }
            }
        }

        println("");
        println("Scanned " + scanned + " instructions total. Found " + hitCount
            + " instruction(s) referencing offset 0x" + Long.toHexString(targetOffset)
            + " (" + targetOffset + ").");
        if (hitCount == 0) {
            println("No hits -- the offset may be computed dynamically rather than appearing "
                + "as a literal displacement, or accessed via a different addressing mode "
                + "this scan doesn't catch (e.g. pre-added into a register elsewhere).");
        }
    }
}
