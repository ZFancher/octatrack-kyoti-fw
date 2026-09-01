//@category Octatrack
// 1) Full decompile of the manual scene writers (B = FUN_40052944, find the A analog).
// 2) Every call SITE of FUN_40009094 with the surrounding instructions, to see which
//    pattern argument each caller passes (does it ever get a non-active pattern?).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraSceneAssign extends GhidraScript {
  public void run() throws Exception {
    var sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var lst = currentProgram.getListing();
    DecompInterface d = new DecompInterface(); d.openProgram(currentProgram);
    var mon = new ConsoleTaskMonitor();

    // --- find every function writing 0x100a4ede (scene A live) or 0x100a4edf (scene B live)
    println("=== functions touching 0x100a4ede / 0x100a4edf ===");
    Set<Function> writers = new LinkedHashSet<>();
    var it = lst.getInstructions(true);
    while (it.hasNext()) {
      Instruction ins = it.next();
      for (int o = 0; o < ins.getNumOperands(); o++)
        for (Object ob : ins.getOpObjects(o))
          if (ob instanceof Scalar) {
            long v = ((Scalar) ob).getUnsignedValue();
            if (v == 0x100a4edeL || v == 0x100a4edfL) {
              Function f = fm.getFunctionContaining(ins.getAddress());
              if (f != null) { writers.add(f); println("  0x" + Long.toHexString(v) + " @" + ins.getAddress() + " in " + f.getName()); }
            }
          }
    }

    // --- call sites of FUN_40009094 with context
    println("\n=== call sites of FUN_40009094 (part, pattern) ===");
    Address target = sp.getAddress(0x40009094L);
    var ri = currentProgram.getReferenceManager().getReferencesTo(target);
    while (ri.hasNext()) {
      var r = ri.next();
      if (!r.getReferenceType().isCall()) continue;
      Function f = fm.getFunctionContaining(r.getFromAddress());
      println("\n  -- from " + (f != null ? f.getName() : "?") + " @ " + r.getFromAddress() + " --");
      Instruction ins = lst.getInstructionAt(r.getFromAddress());
      // print the 10 instructions before the call (argument setup)
      List<String> ctx = new ArrayList<>();
      Instruction p = ins;
      for (int i = 0; i < 10 && p != null; i++) { p = p.getPrevious(); if (p != null) ctx.add(0, "      " + p.getAddress() + "  " + p); }
      for (String s : ctx) println(s);
      println("   -> " + ins.getAddress() + "  " + ins);
    }

    println("\n=== decompiled scene writers ===");
    for (Function f : writers) {
      var res = d.decompileFunction(f, 240, mon);
      println("\n#### " + f.getName() + " @ " + f.getEntryPoint() + " size=" + f.getBody().getNumAddresses() + " ####");
      println(res != null && res.decompileCompleted() ? res.getDecompiledFunction().getC() : "(no-C)");
    }
    println("\n[END]");
  }
}
