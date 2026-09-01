//@category Octatrack
// GhidraResolve7 found the real instruction starts (pea/move.l at 4006e810,
// 40081f64, 400826d4) but decompile kept failing -- because earlier scripts'
// failed disassemble() attempts left broken stub "functions" committed at the
// wrong (operand-middle) addresses in the persisted Ghidra project. Remove those
// stale stubs first, then create correct functions at the real starts.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import java.util.*;

public class GhidraResolve8 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var lst = currentProgram.getListing();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    // pairs: [stale bad address, real instruction-start address]
    long[][] pairs = {
      {0x4006e812L, 0x4006e810L},
      {0x40081f66L, 0x40081f64L},
      {0x400826d6L, 0x400826d4L}
    };

    for (long[] p : pairs) {
      Address staleAddr = af.getAddress(p[0]);
      Address realStart = af.getAddress(p[1]);
      println("\n\n########## " + realStart + " (cleaning up stale stub near " + staleAddr + ") ##########");

      Function staleFn = fm.getFunctionContaining(staleAddr);
      if (staleFn != null) {
        println("removing stale function: " + staleFn.getName() + " @ " + staleFn.getEntryPoint());
        try {
          fm.removeFunction(staleFn.getEntryPoint());
        } catch (Exception e) {
          println("  removeFunction failed: " + e);
        }
      } else {
        println("no stale function found containing " + staleAddr);
      }

      // clear a generous window and redo disassembly cleanly
      try {
        clearListing(realStart.subtract(4), realStart.add(40));
      } catch (Exception e) {
        println("clearListing failed: " + e);
      }
      try {
        disassemble(realStart);
      } catch (Exception e) {
        println("disassemble failed: " + e);
        continue;
      }

      Function f = fm.getFunctionContaining(realStart);
      if (f == null) {
        try {
          f = createFunction(realStart, "direct_ref_" + realStart);
        } catch (Exception e) {
          println("createFunction failed: " + e);
        }
      }
      if (f == null) {
        println("still no function at " + realStart);
        continue;
      }

      println("function: " + f.getName() + " @ " + f.getEntryPoint()
              + "  body " + f.getBody().getMinAddress() + "-" + f.getBody().getMaxAddress());

      var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
      if (res != null && res.decompileCompleted()) {
        println(res.getDecompiledFunction().getC());
      } else {
        println("  (decompile failed: " + (res != null ? res.getErrorMessage() : "no result") + ")");
        // fall back: print raw disassembly of the function body so we have *something*
        Instruction ins = lst.getInstructionAt(f.getEntryPoint());
        println("  raw disassembly fallback:");
        for (int i = 0; i < 60 && ins != null; i++) {
          println("    " + ins.getAddress() + "  " + ins.toString());
          ins = ins.getNext();
        }
      }
    }
    println("\n[END]");
  }
}
