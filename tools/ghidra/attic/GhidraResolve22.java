// GhidraResolve22.java
//
// BREAKTHROUGH from GhidraResolve21: case 'D' in FUN_40061a94 (the main
// message-dispatch loop) contains the real manual-trig-key fork:
//
//   cVar1 = per-track type byte @ (_DAT_46c82456 + track + pattern*0x18b2 + 0x8eda2)
//   if (cVar1 == 4) -> FUN_40097924(...) -> trig_to_voice()      [AUDIO, confirmed]
//   else            -> FUN_40083544(track, iVar9)                [UNKNOWN -- likely MIDI]
//
// This script decompiles FUN_40083544 in full, and separately scans its
// body for any instruction touching offset 0x129 (our confirmed trig-mode
// field: -1=DIRECT, 0-16=quantize length). A hit here would be the
// strongest evidence yet that we've found the actual manual DIRECT-trig
// handling code for non-audio (likely MIDI) tracks.
//
// Also dumps the call graph out of FUN_40083544 (like GhidraResolve19 did
// for candidate_400a1f2e) so we get its shape without reading raw C by eye
// if it turns out to be large.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.RefType;

import java.util.LinkedHashMap;
import java.util.Map;

public class GhidraResolve22 extends GhidraScript {

    private static final long TARGET = 0x40083544L;
    private static final long TRIG_MODE_OFFSET = 0x129L;

    @Override
    public void run() throws Exception {
        Address addr = toAddr(TARGET);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        if (f == null) {
            println("No function defined at " + addr + " -- aborting.");
            return;
        }

        println("=========================================================");
        println("PART 1: Decompiling " + f.getName() + " @ " + addr);
        println("=========================================================");
        println("Body size: " + f.getBody().getNumAddresses() + " bytes");

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

        println("");
        println("=========================================================");
        println("PART 2: Scanning for offset 0x129 (trig-mode field) inside this function");
        println("=========================================================");
        Listing listing = currentProgram.getListing();
        InstructionIterator it = listing.getInstructions(f.getBody(), true);
        int hits = 0;
        while (it.hasNext()) {
            Instruction insn = it.next();
            for (int opIndex = 0; opIndex < insn.getNumOperands(); opIndex++) {
                for (Object obj : insn.getOpObjects(opIndex)) {
                    if (obj instanceof Scalar) {
                        Scalar s = (Scalar) obj;
                        if (s.getSignedValue() == TRIG_MODE_OFFSET || s.getUnsignedValue() == TRIG_MODE_OFFSET) {
                            println("  [0x129 HIT] " + insn.getAddress() + "  \"" + insn.toString() + "\"");
                            hits++;
                        }
                    }
                }
            }
        }
        println("Total 0x129 hits: " + hits);

        println("");
        println("=========================================================");
        println("PART 3: Call graph out of " + f.getName());
        println("=========================================================");
        Map<String, Integer> callCounts = new LinkedHashMap<>();
        Map<String, Long> firstSite = new LinkedHashMap<>();
        InstructionIterator it2 = listing.getInstructions(f.getBody(), true);
        while (it2.hasNext()) {
            Instruction insn = it2.next();
            Address at = insn.getAddress();
            Reference[] refs = currentProgram.getReferenceManager().getReferencesFrom(at);
            for (Reference r : refs) {
                RefType rt = r.getReferenceType();
                if (rt != null && rt.isCall()) {
                    Address to = r.getToAddress();
                    Function callee = fm.getFunctionAt(to);
                    String name = (callee != null) ? callee.getName() : ("UNRESOLVED@" + to);
                    callCounts.merge(name, 1, Integer::sum);
                    firstSite.putIfAbsent(name, at.getOffset());
                }
            }
        }
        for (Map.Entry<String, Integer> e : callCounts.entrySet()) {
            println("  calls " + e.getKey() + "  (x" + e.getValue()
                + ", first at 0x" + Long.toHexString(firstSite.get(e.getKey())) + ")");
        }
        println("Total distinct callees: " + callCounts.size());

        println("");
        println("=== GhidraResolve22 complete ===");
    }
}
