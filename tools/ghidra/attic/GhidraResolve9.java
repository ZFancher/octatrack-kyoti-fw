//@category Octatrack
// direct_ref_40081f64 and direct_ref_400826d4 are near-identical "trig mode label
// getter" functions reading from two different tables (0x400b2f52, 0x400b2ff2).
// Find their callers (should be real, formal xrefs since these are direct calls,
// not data-table lookups) and decompile them -- that should land us in the audio
// vs MIDI track setup page code. Also dump both label tables (indices 0..16) so
// we can tell which function is audio and which is MIDI from the label text.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class GhidraResolve9 extends GhidraScript {

  String readCString(Memory mem, Address a, int max) {
    StringBuilder sb = new StringBuilder();
    try {
      for (int i = 0; i < max; i++) {
        byte b = mem.getByte(a.add(i));
        if (b == 0) break;
        if (b >= 32 && b < 127) sb.append((char) b);
        else sb.append('.');
      }
    } catch (Exception e) {
      return "<unreadable>";
    }
    return sb.toString();
  }

  void dumpTable(Memory mem, long base, String label) {
    println("\n-- table @ 0x" + Long.toHexString(base) + " (" + label + ") --");
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    for (int i = 0; i < 17; i++) {
      Address entryAddr = af.getAddress(base + (long) i * 4);
      try {
        int strPtr = mem.getInt(entryAddr);
        Address strAddr = af.getAddress(strPtr & 0xffffffffL);
        String s = readCString(mem, strAddr, 24);
        println("  [" + i + "] -> 0x" + Integer.toHexString(strPtr) + "  \"" + s + "\"");
      } catch (Exception e) {
        println("  [" + i + "] <error: " + e + ">");
      }
    }
  }

  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var rm = currentProgram.getReferenceManager();
    Memory mem = currentProgram.getMemory();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    long[] targets = {0x40081f64L, 0x400826d4L};
    for (long tv : targets) {
      Address t = af.getAddress(tv);
      println("\n\n########## callers of " + t + " ##########");
      var refs = rm.getReferencesTo(t);
      Set<String> funcKeys = new LinkedHashSet<>();
      int n = 0;
      while (refs.hasNext()) {
        Reference r = refs.next();
        n++;
        Function caller = fm.getFunctionContaining(r.getFromAddress());
        println("  " + r.getFromAddress() + "  " + r.getReferenceType()
                + "  in " + (caller != null ? caller.getName() + " @ " + caller.getEntryPoint() : "(no function)"));
        if (caller != null) funcKeys.add(caller.getName() + "|" + caller.getEntryPoint());
      }
      if (n == 0) println("  (no formal references found)");

      for (String key : funcKeys) {
        String[] parts = key.split("\\|");
        Address fa = af.getAddress(parts[1]);
        Function f = fm.getFunctionAt(fa);
        if (f == null) continue;
        var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
        println("\n  ---- caller: " + f.getName() + " @ " + f.getEntryPoint() + " ----");
        if (res != null && res.decompileCompleted()) {
          println(res.getDecompiledFunction().getC());
        } else {
          println("  (decompile failed)");
        }
      }
    }

    dumpTable(mem, 0x400b2f52L, "table A (from direct_ref_40081f64)");
    dumpTable(mem, 0x400b2ff2L, "table B (from direct_ref_400826d4)");

    println("\n[END]");
  }
}
