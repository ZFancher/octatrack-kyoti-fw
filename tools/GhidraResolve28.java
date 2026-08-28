// GhidraResolve28.java
// Save as tools/GhidraResolve28.java.
//
// Session 4 continued (part 3): the per-track SCALE_MODE byte found this session
// (blob-relative +0x48fd) is read nowhere inside FUN_400a1eea (GhidraResolve27 checked).
// A previous whole-image search for the literal DIRECT offset (+0x48fe) via raw byte
// scanning missed its real reader entirely, because it's accessed via register-relative
// displacement addressing ((0x48fe,A0)-style), which does not appear as a contiguous
// "0x00 0x00 0x48 0xfe" byte run in the binary the way an absolute-addressing access would.
// Ghidra's own decoded-instruction operands don't have that blind spot -- they resolve to
// the same integer value regardless of addressing mode/encoding width.
//
// This script scans EVERY instruction in the ENTIRE program (not just one function) for any
// operand (Scalar or Address) equal to 0x48fc, 0x48fd, or 0x48fe -- i.e. PLAYS_FREE,
// SCALE_MODE, and TRIGQUANT/DIRECT respectively -- and prints, per hit: containing function
// name/address, the instruction itself, and which of the 3 target values matched. Also
// tallies which functions reference 2+ of the three -- the strongest remaining candidate
// list for the still-missing MIDI manual-trig handler (needs to read PLAYS_FREE, SCALE_MODE,
// and DIRECT together to gate the reported bug).
//
// Run headless (same pattern as GhidraResolve26/27):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve28.java

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.address.Address;
import ghidra.program.model.scalar.Scalar;
import java.util.*;

public class GhidraResolve28 extends GhidraScript {
    public void run() throws Exception {
        Map<Long,String> targets = new LinkedHashMap<>();
        targets.put(0x48fcL, "PLAYS_FREE");
        targets.put(0x48fdL, "SCALE_MODE");
        targets.put(0x48feL, "DIRECT");

        FunctionManager fm = currentProgram.getFunctionManager();
        Map<String, Set<String>> funcHits = new LinkedHashMap<>(); // funcName@addr -> set of field names
        int totalInsn = 0;

        InstructionIterator it = currentProgram.getListing().getInstructions(true);
        while (it.hasNext()) {
            Instruction insn = it.next();
            totalInsn++;
            int numOps = insn.getNumOperands();
            Set<String> matched = new HashSet<>();
            for (int op = 0; op < numOps; op++) {
                Object[] objs = insn.getOpObjects(op);
                for (Object o : objs) {
                    Long v = null;
                    if (o instanceof Scalar) v = ((Scalar) o).getUnsignedValue();
                    if (o instanceof Address) v = ((Address) o).getOffset();
                    if (v != null && targets.containsKey(v)) matched.add(targets.get(v));
                }
            }
            if (!matched.isEmpty()) {
                Function f = fm.getFunctionContaining(insn.getAddress());
                String key = (f != null) ? (f.getName() + "@" + f.getEntryPoint()) : ("NOFUNC@" + insn.getAddress());
                println(String.format("HIT %-40s %s  %s  [%s]", key, insn.getAddress(), insn.toString(), String.join(",", matched)));
                funcHits.computeIfAbsent(key, k -> new HashSet<>()).addAll(matched);
            }
        }

        println("\nTotal instructions scanned: " + totalInsn);
        println("\n==== Functions referencing 2+ of {PLAYS_FREE, SCALE_MODE, DIRECT} ====");
        for (Map.Entry<String,Set<String>> e : funcHits.entrySet()) {
            if (e.getValue().size() >= 2) {
                println(e.getKey() + "  ->  " + e.getValue());
            }
        }
        println("\n==== All functions with any hit, and which field(s) ====");
        for (Map.Entry<String,Set<String>> e : funcHits.entrySet()) {
            println(e.getKey() + "  ->  " + e.getValue());
        }
        println("\nDone.");
    }
}
