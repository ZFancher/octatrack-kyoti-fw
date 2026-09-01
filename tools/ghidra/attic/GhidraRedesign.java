//@category Octatrack
// Redesign groundwork:
//  Q1 who READS vs WRITES the scene A/B selection (both address forms)
//  Q2 what is FUN_40033e3c(8, 0x37|0x38, scene) -- the display/LED call made by the
//     manual scene writer; is it the trig-LED painter, and does it carry a colour?
//  Q3 who else calls FUN_40033e3c and with what selector
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraRedesign extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; FunctionManager fm; Listing lst;

  void dump(long a, String tag) throws Exception {
    Function f = fm.getFunctionAt(currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a));
    if (f == null) { println("no function @" + Long.toHexString(a)); return; }
    var r = d.decompileFunction(f, 300, mon);
    println("\n#### " + f.getName() + " @ " + f.getEntryPoint()
            + " size=" + f.getBody().getNumAddresses() + " (" + tag + ") ####");
    println(r != null && r.decompileCompleted() ? r.getDecompiledFunction().getC() : "(no-C)");
  }

  public void run() throws Exception {
    fm = currentProgram.getFunctionManager(); lst = currentProgram.getListing();
    d = new DecompInterface(); d.openProgram(currentProgram);
    mon = new ConsoleTaskMonitor();
    var sp = currentProgram.getAddressFactory().getDefaultAddressSpace();

    println("=== Q1: every instruction touching the scene selection ===");
    long[] want = {0x8ed90L, 0x8ed91L, 0x100a4edeL, 0x100a4edfL};
    Set<String> fns = new TreeSet<>();
    var it = lst.getInstructions(true);
    while (it.hasNext()) {
      Instruction ins = it.next();
      for (int o = 0; o < ins.getNumOperands(); o++)
        for (Object ob : ins.getOpObjects(o))
          if (ob instanceof Scalar) {
            long v = ((Scalar) ob).getUnsignedValue();
            for (long w : want) if (v == w) {
              Function f = fm.getFunctionContaining(ins.getAddress());
              String n = f != null ? f.getName() : "?";
              println(String.format("  0x%-8x @%s  %-42s in %s", v, ins.getAddress(), ins.toString(), n));
              fns.add(n + "@" + (f != null ? f.getEntryPoint() : "?"));
            }
          }
    }
    println("  functions: " + fns);

    println("\n=== Q3: callers of FUN_40033e3c (display) with argument setup ===");
    Address t = sp.getAddress(0x40033e3cL);
    var ri = currentProgram.getReferenceManager().getReferencesTo(t);
    int n = 0;
    while (ri.hasNext()) {
      var r = ri.next();
      if (!r.getReferenceType().isCall()) continue;
      Function f = fm.getFunctionContaining(r.getFromAddress());
      Instruction ins = lst.getInstructionAt(r.getFromAddress());
      StringBuilder sb = new StringBuilder();
      Instruction p = ins;
      List<String> ctx = new ArrayList<>();
      for (int i = 0; i < 6 && p != null; i++) { p = p.getPrevious(); if (p != null) ctx.add(0, p.toString()); }
      println("  " + (f != null ? f.getName() : "?") + " @" + r.getFromAddress() + " : " + String.join(" ; ", ctx));
      n++;
      if (n > 60) { println("  ... (truncated)"); break; }
    }
    println("  (" + n + " call sites)");

    dump(0x40033e3cL, "Q2 display/LED");
    println("\n[END]");
  }
}
