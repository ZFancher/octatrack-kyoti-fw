//@category Octatrack
// Follow-up: is 0x100a4ede the same memory as *(0x46c82456)+0x8ed90, and do the
// scene-selection *loaders* (FUN_4004a100 / FUN_400a0734) run on a Part change and
// overwrite what scene_stub wrote?
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMirror2 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;

  String c(Function f) throws Exception {
    DecompileResults r = dec.decompileFunction(f, 240, mon);
    return r != null && r.decompileCompleted() ? r.getDecompiledFunction().getC() : "  (no-C)";
  }

  void callers(long addr, String label) {
    Address ad = sp.getAddress(addr);
    Function f = fm.getFunctionAt(ad);
    println("\n--- callers of " + label + " @ " + Long.toHexString(addr) + " ---");
    if (f == null) { println("  (no function at address)"); return; }
    Set<String> seen = new TreeSet<>();
    for (Function cf : f.getCallingFunctions(mon)) seen.add(cf.getName() + " @ " + cf.getEntryPoint());
    if (seen.isEmpty()) println("  (none found — likely called via pointer/table)");
    for (String s : seen) println("  " + s);
  }

  public void run() throws Exception {
    sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm = currentProgram.getFunctionManager();
    dec = new DecompInterface(); dec.openProgram(currentProgram);
    mon = new ConsoleTaskMonitor();
    Listing lst = currentProgram.getListing();

    // 1. Is the project base a compile-time constant 0x1001614e?
    println("=== sites referencing 0x1001614e (candidate project base) ===");
    long CAND = 0x1001614eL;
    int n = 0;
    InstructionIterator it = lst.getInstructions(true);
    while (it.hasNext()) {
      Instruction ins = it.next();
      for (int o = 0; o < ins.getNumOperands(); o++)
        for (Object ob : ins.getOpObjects(o))
          if (ob instanceof Scalar && ((Scalar) ob).getUnsignedValue() == CAND) {
            Function f = fm.getFunctionContaining(ins.getAddress());
            println("  @" + ins.getAddress() + "  [" + ins + "]  in " + (f != null ? f.getName() : "?"));
            n++;
          }
    }
    println("  (" + n + " sites)");

    // 2. Who writes the project-base pointer 0x46c82456?
    println("\n=== sites referencing 0x46c82456 (project base pointer) ===");
    n = 0;
    it = lst.getInstructions(true);
    while (it.hasNext()) {
      Instruction ins = it.next();
      for (int o = 0; o < ins.getNumOperands(); o++)
        for (Object ob : ins.getOpObjects(o))
          if (ob instanceof Scalar && ((Scalar) ob).getUnsignedValue() == 0x46c82456L) {
            Function f = fm.getFunctionContaining(ins.getAddress());
            println("  @" + ins.getAddress() + "  [" + ins + "]  in " + (f != null ? f.getName() : "?"));
            n++;
          }
    }
    println("  (" + n + " sites)");

    // 3. Callers of the loaders and of the patched part-apply
    callers(0x4004a100L, "FUN_4004a100 (scene-sel loader A)");
    callers(0x400a0734L, "FUN_400a0734 (scene-sel loader B)");
    callers(0x40009094L, "FUN_40009094 (part apply, our detour host)");
    callers(0x4003f1b4L, "FUN_4003f1b4 (crossfader)");

    // 4. Full decompilation of the two loaders
    for (long a : new long[]{0x4004a100L, 0x400a0734L}) {
      Function f = fm.getFunctionAt(sp.getAddress(a));
      println("\n#### " + f.getName() + " @ " + f.getEntryPoint() + " ####");
      println(c(f));
    }
    println("\n[END]");
  }
}
