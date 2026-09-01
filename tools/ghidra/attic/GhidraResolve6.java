//@category Octatrack
// The 3 "ptr-to DIRECT string" hits from GhidraResolve5 are raw data table entries
// (disassembly correctly failed on them). For each, walk outward to find the
// table's real start/end (a run of consecutive 4-byte values in the 0x40xxxxxx
// code/data range), then search all of memory for raw pointer references to the
// table's START address (the "lea table_base,Ax" style load), and decompile
// whatever function contains each such reference.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class GhidraResolve6 extends GhidraScript {

  boolean looksLikeAddr(Memory mem, Address a) throws Exception {
    byte[] b = new byte[4];
    mem.getBytes(a, b);
    int top = b[0] & 0xff;
    return top == 0x40; // this firmware's code/rodata all lives under 0x40xxxxxx
  }

  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    Memory mem = currentProgram.getMemory();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    long[] hitAddrs = {0x4006e812L, 0x40081f66L, 0x400826d6L};

    for (long hv : hitAddrs) {
      Address hit = af.getAddress(hv);
      println("\n\n########## table containing " + hit + " ##########");

      // walk backward
      Address start = hit;
      while (true) {
        Address prev = start.subtract(4);
        if (!looksLikeAddr(mem, prev)) break;
        start = prev;
      }
      // walk forward
      Address end = hit;
      while (true) {
        Address next = end.add(4);
        if (!looksLikeAddr(mem, next)) break;
        end = next;
      }
      long count = (end.subtract(start)) / 4 + 1;
      println("table start=" + start + " end=" + end + " entries=" + count);

      // search for raw pointer refs to the table START
      long tv = start.getOffset();
      byte[] pattern = new byte[] {
        (byte) ((tv >> 24) & 0xff), (byte) ((tv >> 16) & 0xff),
        (byte) ((tv >> 8) & 0xff), (byte) (tv & 0xff)
      };
      Address c = currentProgram.getMinAddress();
      Set<String> funcKeys = new LinkedHashSet<>();
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
        println("  ptr-to table-start found @ " + found
                + (f != null ? "  in " + f.getName() + " @ " + f.getEntryPoint() : "  (no function / data)"));
        if (f != null) funcKeys.add(f.getName() + "|" + f.getEntryPoint());
        Address next = found.add(1);
        if (next.compareTo(currentProgram.getMaxAddress()) >= 0) break;
        c = next;
      }

      for (String key : funcKeys) {
        String[] parts = key.split("\\|");
        Address fa = af.getAddress(parts[1]);
        Function f = fm.getFunctionAt(fa);
        if (f == null) continue;
        var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
        println("\n  ---- " + f.getName() + " @ " + f.getEntryPoint() + " ----");
        if (res != null && res.decompileCompleted()) {
          println(res.getDecompiledFunction().getC());
        } else {
          println("  (decompile failed)");
        }
      }
    }
    println("\n[END]");
  }
}
