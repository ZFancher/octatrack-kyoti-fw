// GhidraResolve35.java
// Session 4 continued (part 7): user corrected DAT_80000012/"MIDI_MODE" -- pattern scale
// mode ("per track" vs "normal") is a PATTERN-LEVEL setting (can differ pattern to pattern
// within a bank), not a project-level one, so DAT_80000012 (a single global loaded once from
// project state) cannot be it. The real candidate, flagged back in part 3/GhidraResolve27,
// is blob-relative +0x8e55 -- a pattern-level byte (near the end of each 0x8ed8-byte pattern
// block) that FUN_400a1eea's quantize engine tests to choose between a pattern-wide fallback
// quantize length (+0x8e53) and a per-track one (+0x48f8). That's a natural fit for
// "scale = per track" vs "normal". It also matches the confirmed diff: +0x8e55 flips
// 0x00->0x01 in the exact same test-pair diff as +0x48fd (TRIG_MODE).
//
// Open question: does the manual-trig-key dispatch chain (FUN_40044584 -> FUN_4009b5c8 /
// FUN_4009f3a4) read +0x8e55 (or anything pattern-scale-related) ANYWHERE? If DIRECT alone
// already forces the buggy branch via `cVar1 == -1`, scale-mode shouldn't matter for that
// specific OR'd condition -- unless scale-mode feeds into _DAT_800065b8 (the "stepping"
// flag) or gates something else entirely we haven't traced yet. This script does a full
// whole-image operand scan for 0x8e55 AND 0x8e53 (not just within FUN_400a1eea, which is
// all that was checked before) to find every reader/writer of both bytes, wherever they are.
//
// Run headless (same pattern as GhidraResolve26-34):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve35.java

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.address.Address;
import ghidra.program.model.scalar.Scalar;
import java.util.*;

public class GhidraResolve35 extends GhidraScript {
    public void run() throws Exception {
        Map<Long,String> targets = new LinkedHashMap<>();
        targets.put(0x8e55L, "SCALE_MODE_pattern_flag");
        targets.put(0x8e53L, "pattern_fallback_quantlen");
        targets.put(0x48f8L, "per_track_quantlen");

        FunctionManager fm = currentProgram.getFunctionManager();
        Map<String, Set<String>> funcHits = new LinkedHashMap<>();
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
        println("\n==== Functions referencing any of {0x8e55, 0x8e53, 0x48f8} ====");
        for (Map.Entry<String,Set<String>> e : funcHits.entrySet()) {
            println(e.getKey() + "  ->  " + e.getValue());
        }
        println("\nDone.");
    }
}
