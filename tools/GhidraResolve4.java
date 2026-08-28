//@category Octatrack
// FUN_400977cc turned out to be the AUDIO track trig dispatcher (machine types
// 0-4 = static/flex/thru/neighbor/pickup per ARCHITECTURE.md), not MIDI. MIDI
// tracks use a separate state struct at 0x80006500[t] (per ARCHITECTURE.md's
// memory map). Search all executable memory for code that loads this address
// as an immediate -- that should lead us to the MIDI-specific trig/step logic.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class GhidraResolve4 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var lst = currentProgram.getListing();
    Memory mem = currentProgram.getMemory();

    long[] targets = {0x80006500L, 0x800065b8L};
    String[] names = {"midi_track_state_base", "midi_track_state_global"};

    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    for (int t = 0; t < targets.length; t++) {
      long val = targets[t];
      byte[] pattern = new byte[] {
        (byte)((val >> 24) & 0xff), (byte)((val >> 16) & 0xff),
        (byte)((val >> 8) & 0xff), (byte)(val & 0xff)
      };
      println("\n\n########## searching for 0x" + Long.toHexString(val) + " (" + names[t] + ") ##########");

      Address start = currentProgram.getMinAddress();
      Address found = mem.findBytes(start, pattern, null, true, monitor);
      int hits = 0;
      Set<String> seenFuncs = new LinkedHashSet<>();
      while (found != null && hits < 40) {
        hits++;
        Function f = fm.getFunctionContaining(found);
        String fname = "(no function)";
        if (f == null) {
          try {
            disassemble(found);
            f = fm.getFunctionContaining(found);
            if (f == null) f = createFunction(found, "candidate_" + found);
          } catch (Exception e) {}
        }
        if (f != null) fname = f.getName() + " @ " + f.getEntryPoint();
        println("  hit " + hits + ": " + found + "  in " + fname);
        if (f != null) seenFuncs.add(f.getName() + "|" + f.getEntryPoint());

        Address next = found.add(1);
        if (next.compareTo(currentProgram.getMaxAddress()) >= 0) break;
        found = mem.findBytes(next, pattern, null, true, monitor);
      }
      if (hits == 0) {
        println("  (no hits)");
        continue;
      }

      println("\n  -- decompiling " + seenFuncs.size() + " distinct containing function(s) --");
      for (String key : seenFuncs) {
        String[] parts = key.split("\\|");
        Address fa = af.getAddress(parts[1]);
        Function f = fm.getFunctionAt(fa);
        if (f == null) continue;
        var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
        println("\n  ==== " + f.getName() + " @ " + f.getEntryPoint() + " ====");
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
