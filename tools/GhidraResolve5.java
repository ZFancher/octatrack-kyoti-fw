//@category Octatrack
// Corrected bug target: MIDI track, launch mode DIRECT, manually trigged -> plays
// step 1 then stops (should play through the whole sequence, like audio tracks in
// DIRECT do, whether the sequencer is running or stopped). Audio DIRECT works
// correctly, so it's our reference implementation.
//
// This script: (1) finds every occurrence of the ASCII string "DIRECT" in memory,
// (2) for each, searches all of memory for raw 4-byte pointers to that address
// (the "table entry, not a formal xref" pattern we hit repeatedly last session),
// (3) forces disassembly + function creation at each hit, and (4) decompiles
// every distinct containing function. One pass, no manual GUI round-trips.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class GhidraResolve5 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    Memory mem = currentProgram.getMemory();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    // --- Phase 1: find "DIRECT" string occurrences ---
    byte[] needle = "DIRECT".getBytes("US-ASCII");
    List<Address> stringHits = new ArrayList<>();
    Address cursor = currentProgram.getMinAddress();
    while (true) {
      Address found = mem.findBytes(cursor, needle, null, true, monitor);
      if (found == null) break;
      stringHits.add(found);
      Address next = found.add(1);
      if (next.compareTo(currentProgram.getMaxAddress()) >= 0) break;
      cursor = next;
    }
    println("Found " + stringHits.size() + " occurrences of \"DIRECT\":");
    for (Address a : stringHits) {
      byte[] ctx = new byte[24];
      try { mem.getBytes(a, ctx); } catch (Exception e) {}
      StringBuilder sb = new StringBuilder();
      for (byte b : ctx) sb.append((b >= 32 && b < 127) ? (char) b : '.');
      println("  " + a + "  \"" + sb + "\"");
    }

    // --- Phase 2: for each string hit, find raw pointer references to it ---
    Set<String> allFuncKeys = new LinkedHashSet<>();
    for (Address strAddr : stringHits) {
      long val = strAddr.getOffset();
      byte[] pattern = new byte[] {
        (byte) ((val >> 24) & 0xff), (byte) ((val >> 16) & 0xff),
        (byte) ((val >> 8) & 0xff), (byte) (val & 0xff)
      };
      Address c = currentProgram.getMinAddress();
      int hits = 0;
      while (hits < 20) {
        Address found = mem.findBytes(c, pattern, null, true, monitor);
        if (found == null) break;
        hits++;
        Function f = fm.getFunctionContaining(found);
        if (f == null) {
          try {
            disassemble(found);
            f = fm.getFunctionContaining(found);
            if (f == null) f = createFunction(found, "candidate_" + found);
          } catch (Exception e) {}
        }
        String fname = f != null ? f.getName() + "|" + f.getEntryPoint() : null;
        println("  ptr-to " + strAddr + " found @ " + found
                + (f != null ? "  in " + f.getName() + " @ " + f.getEntryPoint() : "  (no function)"));
        if (fname != null) allFuncKeys.add(fname);
        Address next = found.add(1);
        if (next.compareTo(currentProgram.getMaxAddress()) >= 0) break;
        c = next;
      }
    }

    println("\n=== decompiling " + allFuncKeys.size() + " distinct containing function(s) ===");
    for (String key : allFuncKeys) {
      String[] parts = key.split("\\|");
      Address fa = af.getAddress(parts[1]);
      Function f = fm.getFunctionAt(fa);
      if (f == null) continue;
      var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
      println("\n---- " + f.getName() + " @ " + f.getEntryPoint() + " ----");
      if (res != null && res.decompileCompleted()) {
        println(res.getDecompiledFunction().getC());
      } else {
        println("  (decompile failed)");
      }
    }
    println("\n[END]");
  }
}
