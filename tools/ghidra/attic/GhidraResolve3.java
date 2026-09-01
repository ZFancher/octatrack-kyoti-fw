//@category Octatrack
// Decompile FUN_400977cc (trig -> voice dispatch, per ARCHITECTURE.md) plus its
// immediate callers and callees, to find the per-track step/position advance
// logic relevant to the MIDI Plays Free + Scale Per Track + Direct launch bug.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;

public class GhidraResolve3 extends GhidraScript {

  DecompInterface dec;

  String decompileAt(long addr, String label) {
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm = currentProgram.getFunctionManager();
    Address a = af.getAddress(addr);
    Function f = fm.getFunctionContaining(a);
    if (f == null) {
      try { disassemble(a); f = createFunction(a, label); } catch (Exception e) {}
    }
    if (f == null) return "  (could not resolve function at 0x" + Long.toHexString(addr) + ")";
    var res = dec.decompileFunction(f, 90, new ConsoleTaskMonitor());
    if (res != null && res.decompileCompleted()) {
      return "==== " + f.getName() + " @ " + f.getEntryPoint() + " ====\n"
             + res.getDecompiledFunction().getC();
    }
    return "  (decompile failed for " + f.getName() + ")";
  }

  public void run() throws Exception {
    dec = new DecompInterface();
    dec.openProgram(currentProgram);

    long target = 0x400977ccL;
    println(decompileAt(target, "trig_to_voice"));

    // list its callers, so we know what feeds into it
    var af = currentProgram.getAddressFactory().getDefaultAddressSpace();
    var rm = currentProgram.getReferenceManager();
    var fm = currentProgram.getFunctionManager();
    Address a = af.getAddress(target);
    println("\n=== callers of 0x" + Long.toHexString(target) + " ===");
    var refs = rm.getReferencesTo(a);
    while (refs.hasNext()) {
      Reference r = refs.next();
      Function caller = fm.getFunctionContaining(r.getFromAddress());
      println("  " + r.getFromAddress() + "  in " + (caller != null ? caller.getName() : "(no function)"));
    }
    println("\n[END]");
  }
}
