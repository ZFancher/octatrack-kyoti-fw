//@category Octatrack
// Who reads/writes the scene-selection RAM mirror around 0x100a4ede/edf?
// The sticky-scenes patch only writes the project copy (0x8ed90/91); if the UI
// reads this mirror, the LEDs keep showing the destination Part's selection.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMirror extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm;
  Set<Long> seen = new HashSet<>();

  void dump(Function f, String tag) throws Exception {
    if (f == null) return;
    if (!seen.add(f.getEntryPoint().getOffset())) return;
    DecompileResults r = dec.decompileFunction(f, 180, mon);
    println("\n#### " + f.getName() + " @ " + f.getEntryPoint()
            + " size=" + f.getBody().getNumAddresses() + " (" + tag + ") ####");
    println(r != null && r.decompileCompleted() ? r.getDecompiledFunction().getC() : "  (no-C)");
  }

  public void run() throws Exception {
    fm = currentProgram.getFunctionManager();
    dec = new DecompInterface(); dec.openProgram(currentProgram);
    mon = new ConsoleTaskMonitor();
    Listing lst = currentProgram.getListing();

    // Window around the suspected mirror: catch scene A, scene B and neighbours
    final long LO = 0x100a4ec0L, HI = 0x100a4ef0L;

    println("=== scalar operands in [0x" + Long.toHexString(LO) + ", 0x" + Long.toHexString(HI) + "] ===");
    InstructionIterator it = lst.getInstructions(true);
    List<Function> hits = new ArrayList<>();
    LinkedHashSet<String> lines = new LinkedHashSet<>();
    while (it.hasNext()) {
      Instruction ins = it.next();
      for (int o = 0; o < ins.getNumOperands(); o++) {
        for (Object ob : ins.getOpObjects(o)) {
          if (ob instanceof Scalar) {
            long v = ((Scalar) ob).getUnsignedValue();
            if (v >= LO && v <= HI) {
              Function f = fm.getFunctionContaining(ins.getAddress());
              // operand 0 is the source on m68k "move src,dst"; report both for clarity
              lines.add(String.format("0x%x  op%d  @%s  [%s]  in %s",
                        v, o, ins.getAddress(), ins, f != null ? f.getName() : "?"));
              if (f != null) hits.add(f);
            }
          }
        }
      }
    }
    for (String s : lines) println("  " + s);
    println("  (" + lines.size() + " sites, " + new HashSet<>(hits).size() + " distinct functions)");

    // Direct references to the exact addresses too (data refs Ghidra already resolved)
    println("\n=== direct references ===");
    AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    for (long a : new long[]{0x100a4edeL, 0x100a4edfL, 0x100a4ee0L}) {
      Address ad = sp.getAddress(a);
      var ri = currentProgram.getReferenceManager().getReferencesTo(ad);
      int n = 0;
      while (ri.hasNext()) {
        var r = ri.next();
        Function f = fm.getFunctionContaining(r.getFromAddress());
        println(String.format("  0x%x <- %s (%s) in %s", a, r.getFromAddress(),
                r.getReferenceType(), f != null ? f.getName() : "?"));
        if (f != null) hits.add(f);
        n++;
      }
      if (n == 0) println(String.format("  0x%x <- (none)", a));
    }

    println("\n=== decompiled hit functions ===");
    for (Function f : hits) dump(f, "mirror");
    println("\n[END]");
  }
}
