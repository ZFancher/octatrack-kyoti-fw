//@category Octatrack
// Full auto-analysis (including Decompiler Switch Analysis) has now run, unlike
// every prior script tonight which used -noanalysis. Re-check formal references
// to the two trig-mode label getters -- if they're called via a resolved switch/
// jump table, real xrefs should exist now that didn't before.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.Reference;
import java.util.*;

public class GhidraResolve11 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var rm = currentProgram.getReferenceManager();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    long[] targets = {0x40081f64L, 0x400826d4L};
    for (long tv : targets) {
      Address t = af.getAddress(tv);
      println("\n\n########## callers of " + t + " (post-analysis) ##########");
      Function target = fm.getFunctionAt(t);
      println("target function now known as: " + (target != null ? target.getName() : "(none)"));

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
      if (n == 0) println("  (still no formal references)");

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
    println("\n[END]");
  }
}
