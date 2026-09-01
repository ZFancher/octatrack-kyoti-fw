//@category Octatrack
// Resolve exactly which function contains 0x40082eb8 (the "tst.l (0x8,A0,D0*1)"
// table-lookup instruction found via byte-pattern search on the PLAYS FREE string
// address), and print both its disassembly and decompilation directly from the
// program database -- bypassing any stale GUI panel state.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;

public class GhidraResolve1 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var lst = currentProgram.getListing();

    long target = 0x40082eb8L;
    Address a = af.getAddress(target);

    Function f = fm.getFunctionContaining(a);
    println("=== target 0x" + Long.toHexString(target) + " ===");
    if (f == null) {
      println("NOT inside any defined function.");
      println("Nearest instruction context:");
      Instruction ins = lst.getInstructionAt(a);
      if (ins == null) ins = lst.getInstructionBefore(a);
      for (int i = 0; i < 6 && ins != null; i++) {
        println("  " + ins.getAddress() + "  " + ins.toString());
        ins = ins.getNext();
      }
      return;
    }

    println("Containing function: " + f.getName() + " @ " + f.getEntryPoint());
    println("Function body range: " + f.getBody().getMinAddress() + " - " + f.getBody().getMaxAddress());

    println("\n--- raw disassembly around target ---");
    Instruction ins = lst.getInstructionAt(a);
    if (ins == null) ins = lst.getInstructionContaining(a);
    Instruction cur = lst.getInstructionBefore(a);
    for (int i = 0; i < 4 && cur != null; i++) cur = lst.getInstructionBefore(cur.getAddress());
    for (int i = 0; i < 10 && cur != null; i++) {
      String marker = cur.getAddress().equals(a) ? "  >>> " : "      ";
      println(marker + cur.getAddress() + "  " + cur.toString());
      cur = cur.getNext();
    }

    println("\n--- decompilation of " + f.getName() + " ---");
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);
    var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
    if (res != null && res.decompileCompleted()) {
      println(res.getDecompiledFunction().getC());
    } else {
      println("decompile failed: " + (res != null ? res.getErrorMessage() : "no result"));
    }
    println("\n[END]");
  }
}
