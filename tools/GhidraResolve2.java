//@category Octatrack
// 0x40082eb8 was never auto-discovered as code (likely only reached via an
// indirect/computed jump -- a function-pointer or jump table Ghidra's static
// analyzer can't trace). Force disassembly + function creation here, then
// decompile, printing enough surrounding context to find the function's real
// start if createFunction() picks a mid-function address by mistake.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;

public class GhidraResolve2 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var lst = currentProgram.getListing();

    long target = 0x40082eb8L;
    Address a = af.getAddress(target);

    println("=== forcing disassembly at 0x" + Long.toHexString(target) + " ===");

    Instruction existing = lst.getInstructionAt(a);
    if (existing == null) {
      try {
        disassemble(a);
        println("disassemble() called.");
      } catch (Exception e) {
        println("disassemble() threw: " + e);
      }
    } else {
      println("already had an instruction here: " + existing);
    }

    println("\n--- raw disassembly, 12 instructions starting at target (or nearest) ---");
    Instruction cur = lst.getInstructionAt(a);
    if (cur == null) cur = lst.getInstructionAfter(a);
    for (int i = 0; i < 12 && cur != null; i++) {
      println("  " + cur.getAddress() + "  " + cur.toString());
      cur = cur.getNext();
    }

    Function f = fm.getFunctionContaining(a);
    if (f == null) {
      println("\nStill not inside a defined function. Creating one at target address.");
      try {
        f = createFunction(a, "candidate_" + Long.toHexString(target));
      } catch (Exception e) {
        println("createFunction() threw: " + e);
      }
    }

    if (f == null) {
      println("\nCould not establish a function here at all. Stopping.");
      return;
    }

    println("\nFunction: " + f.getName() + " @ " + f.getEntryPoint());
    println("Body range: " + f.getBody().getMinAddress() + " - " + f.getBody().getMaxAddress());

    println("\n--- decompilation ---");
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
