//@category Octatrack
// The 3 hits are isolated pointers embedded as instruction operands (not table
// entries -- GhidraResolve6 confirmed each is a lone 4-byte value, not part of a
// run). disassemble() failed at the exact hit address because that's the middle
// of an instruction (the operand), not its start. m68k/ColdFire instructions are
// word-aligned (2-byte granularity); back up by 2-byte steps until disassembly
// succeeds and produces an instruction whose range actually covers the hit.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import java.util.*;

public class GhidraResolve7 extends GhidraScript {
  public void run() throws Exception {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    var lst = currentProgram.getListing();
    DecompInterface dec = new DecompInterface();
    dec.openProgram(currentProgram);

    long[] hitAddrs = {0x4006e812L, 0x40081f66L, 0x400826d6L};

    for (long hv : hitAddrs) {
      Address hit = af.getAddress(hv);
      println("\n\n########## resolving instruction containing operand @ " + hit + " ##########");

      boolean resolved = false;
      for (int back = 2; back <= 20 && !resolved; back += 2) {
        Address tryAddr = hit.subtract(back);
        // clear any earlier bad attempt at this exact spot, then try fresh
        try {
          clearListing(tryAddr, hit.add(4));
        } catch (Exception e) {}
        try {
          disassemble(tryAddr);
        } catch (Exception e) {
          continue;
        }
        Instruction ins = lst.getInstructionContaining(hit);
        if (ins != null) {
          println("resolved: instruction @ " + ins.getAddress() + "  " + ins.toString()
                  + "  (backed up " + back + " bytes)");
          Function f = fm.getFunctionContaining(hit);
          if (f == null) {
            try { f = createFunction(tryAddr, "candidate_" + tryAddr); } catch (Exception e) {}
          }
          if (f != null) {
            println("containing function: " + f.getName() + " @ " + f.getEntryPoint());
            var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
              println(res.getDecompiledFunction().getC());
            } else {
              println("  (decompile failed: " + (res != null ? res.getErrorMessage() : "no result") + ")");
            }
          } else {
            println("  (still no containing function)");
          }
          resolved = true;
        }
      }
      if (!resolved) {
        println("Could not resolve a valid instruction boundary within 20 bytes before " + hit + ".");
      }
    }
    println("\n[END]");
  }
}
