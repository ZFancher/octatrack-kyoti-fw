//@category Octatrack
// direct_ref_4006e810 (the menu-loop function found earlier) was decompiled before
// full auto-analysis ran, showing unresolved unaff_A2/unaff_A6/unaff_D7 function
// pointers. Now that Stack analysis has run, re-decompile it fresh -- if those
// resolve into real incoming parameters, we may see exactly where the getter
// function pointers (like direct_ref_40081f64/400826d4) get loaded from.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;

public class GhidraResolve12 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    Address a = af.getAddress(0x4006e810L);
    Function f = fm.getFunctionContaining(a);
    if (f == null) {
      println("No function found containing 0x4006e810 post-analysis.");
      return;
    }
    println("function: " + f.getName() + " @ " + f.getEntryPoint()
            + "  body " + f.getBody().getMinAddress() + "-" + f.getBody().getMaxAddress());
    println("parameter count: " + f.getParameterCount());
    for (var p : f.getParameters()) {
      println("  param: " + p.getName() + "  " + p.getDataType() + "  " + p.getVariableStorage());
    }

    var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
    if (res != null && res.decompileCompleted()) {
      println("\n" + res.getDecompiledFunction().getC());
    } else {
      println("(decompile failed: " + (res != null ? res.getErrorMessage() : "no result") + ")");
    }

    println("\n[END]");
  }
}
