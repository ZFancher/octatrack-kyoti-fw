// GhidraResolve19.java
//
// candidate_400a1f2e turned out to be a 12KB monolithic function -- too
// large to read by eye efficiently. Rather than dumping full decompiled
// text again, this extracts structured facts:
//
//   1. Call graph: every function candidate_400a1f2e calls, in address
//      order, deduplicated with call counts. This tells us its real
//      shape without reading 12KB of C.
//
//   2. Scan for offset 0x129 (our confirmed trig-mode field) anywhere in
//      its instructions -- does this function touch it directly?
//
//   3. Scan for the literal 0x2b (43) as a scalar operand -- that's the
//      constant FUN_40097924 compared against for the audio-side manual
//      trig-key gate (param_3 == 0x2b). If MIDI has an equivalent gate,
//      it likely compares against the same key-event code somewhere in
//      this function or one of its callees.
//
//   4. Callers of candidate_400a1f2e itself -- what invokes this giant
//      function in the first place (the real top-level sequencer tick
//      dispatcher we've been looking for).

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.RefType;

import java.util.LinkedHashMap;
import java.util.Map;

public class GhidraResolve19 extends GhidraScript {

    private static final long TARGET = 0x400a1f2eL; // candidate_400a1f2e
    private static final long TRIG_MODE_OFFSET = 0x129L;
    private static final long AUDIO_GATE_CONST = 0x2bL;

    @Override
    public void run() throws Exception {
        Address addr = toAddr(TARGET);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        if (f == null) {
            println("No function at " + addr + " -- aborting.");
            return;
        }

        AddressSetView body = f.getBody();
        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        println("=========================================================");
        println("PART 1: Call graph of " + f.getName() + " (functions it calls)");
        println("=========================================================");

        Map<String, Integer> callCounts = new LinkedHashMap<>();
        Map<String, Long> firstCallSite = new LinkedHashMap<>();

        InstructionIterator it = listing.getInstructions(body, true);
        int offsetHits = 0;
        int gateConstHits = 0;

        while (it.hasNext()) {
            Instruction insn = it.next();
            Address at = insn.getAddress();

            // Call graph: look at CALL-type references from this instruction
            Reference[] refs = refMgr.getReferencesFrom(at);
            for (Reference r : refs) {
                RefType rt = r.getReferenceType();
                if (rt != null && rt.isCall()) {
                    Address to = r.getToAddress();
                    Function callee = fm.getFunctionAt(to);
                    String name = (callee != null) ? callee.getName() : ("UNRESOLVED@" + to);
                    callCounts.merge(name, 1, Integer::sum);
                    firstCallSite.putIfAbsent(name, at.getOffset());
                }
            }

            // Offset 0x129 scan
            for (int opIndex = 0; opIndex < insn.getNumOperands(); opIndex++) {
                for (Object obj : insn.getOpObjects(opIndex)) {
                    if (obj instanceof Scalar) {
                        Scalar s = (Scalar) obj;
                        if (s.getSignedValue() == TRIG_MODE_OFFSET || s.getUnsignedValue() == TRIG_MODE_OFFSET) {
                            println("  [0x129 HIT] " + at + "  \"" + insn.toString() + "\"");
                            offsetHits++;
                        }
                        if (s.getSignedValue() == AUDIO_GATE_CONST || s.getUnsignedValue() == AUDIO_GATE_CONST) {
                            println("  [0x2b HIT]  " + at + "  \"" + insn.toString() + "\"");
                            gateConstHits++;
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, Integer> e : callCounts.entrySet()) {
            println("  calls " + e.getKey() + "  (x" + e.getValue()
                + ", first at 0x" + Long.toHexString(firstCallSite.get(e.getKey())) + ")");
        }
        println("");
        println("Total distinct callees: " + callCounts.size());

        println("");
        println("=========================================================");
        println("PART 2: Summary of scans");
        println("=========================================================");
        println("Offset 0x129 hits inside this function: " + offsetHits);
        println("Constant 0x2b hits inside this function: " + gateConstHits);

        println("");
        println("=========================================================");
        println("PART 3: Callers of " + f.getName() + " itself");
        println("=========================================================");
        ReferenceIterator callerIter = refMgr.getReferencesTo(addr);
        int callerCount = 0;
        while (callerIter.hasNext()) {
            Reference r = callerIter.next();
            Address from = r.getFromAddress();
            Function containing = fm.getFunctionContaining(from);
            String containingName = (containing != null) ? containing.getName() : "(no containing function)";
            println("  from " + from + "  type=" + r.getReferenceType() + "  in function: " + containingName);
            callerCount++;
        }
        if (callerCount == 0) {
            println("  No references found -- reached via computed/table call, same as other cases.");
        }

        println("");
        println("=== GhidraResolve19 complete ===");
    }
}
