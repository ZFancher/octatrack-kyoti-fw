//@category Octatrack
// Zero formal xrefs to either getter function means they're installed as callback
// pointers in a per-parameter descriptor struct (label/getter/setter pattern, same
// shape as the PERSONALIZE menu machinery documented in NOTES.md), called
// indirectly by the generic menu renderer. Search for raw pointer references to
// each function's own address to find that descriptor struct, then dump generous
// context around each hit (both directions) so we can see the struct's other
// fields -- which should include a length-limit or track-type value that finally
// distinguishes the audio vs MIDI copy.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class GhidraResolve10 extends GhidraScript {

  String readCString(Memory mem, Address a, int max) {
    StringBuilder sb = new StringBuilder();
    try {
      for (int i = 0; i < max; i++) {
        byte b = mem.getByte(a.add(i));
        if (b == 0) break;
        sb.append((b >= 32 && b < 127) ? (char) b : '.');
      }
    } catch (Exception e) { return "<unreadable>"; }
    return sb.toString();
  }

  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    Memory mem = currentProgram.getMemory();
    var fm = currentProgram.getFunctionManager();

    long[] targets = {0x40081f64L, 0x400826d4L};
    for (long tv : targets) {
      println("\n\n########## raw pointer refs to function @ 0x" + Long.toHexString(tv) + " ##########");
      byte[] pattern = new byte[] {
        (byte) ((tv >> 24) & 0xff), (byte) ((tv >> 16) & 0xff),
        (byte) ((tv >> 8) & 0xff), (byte) (tv & 0xff)
      };
      Address c = currentProgram.getMinAddress();
      int hits = 0;
      while (hits < 10) {
        Address found = mem.findBytes(c, pattern, null, true, monitor);
        if (found == null) break;
        hits++;
        Function containing = fm.getFunctionContaining(found);
        println("\n  hit " + hits + " @ " + found
                + (containing != null ? "  (inside function " + containing.getName() + ")" : "  (data region)"));

        // dump 15 x 4-byte words of context before and after the hit
        println("  -- surrounding 4-byte words (interpreted as both hex and, if plausible, a string ptr) --");
        for (int off = -60; off <= 60; off += 4) {
          Address wa = found.add(off);
          try {
            int val = mem.getInt(wa);
            String marker = (off == 0) ? " <== HIT" : "";
            String extra = "";
            int top = (val >>> 24) & 0xff;
            if (top == 0x40) {
              // looks like a pointer into code/rodata -- try reading it as a C string
              String s = readCString(mem, af.getAddress(val & 0xffffffffL), 20);
              if (s.length() > 0) extra = "  -> \"" + s + "\"";
            }
            println("    " + wa + "  0x" + String.format("%08x", val) + extra + marker);
          } catch (Exception e) {
            println("    " + wa + "  <unreadable>");
          }
        }

        Address next = found.add(1);
        if (next.compareTo(currentProgram.getMaxAddress()) >= 0) break;
        c = next;
      }
      if (hits == 0) println("  (no raw pointer references found)");
    }
    println("\n[END]");
  }
}
