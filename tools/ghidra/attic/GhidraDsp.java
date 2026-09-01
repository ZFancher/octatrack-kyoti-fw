//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraDsp extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> done = new HashSet<>();
  void dump(long s) throws Exception {
    Address a = sp.getAddress(s);
    Function f = fm.getFunctionContaining(a);
    if (f == null) { disassemble(a); f = createFunction(a, null); }
    if (f == null) { println("[!] sin funcion @ "+Long.toHexString(s)); return; }
    long ep = f.getEntryPoint().getOffset();
    if (!done.add(ep)) return;
    DecompileResults r = dec.decompileFunction(f, 120, mon);
    println("\n============ "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+") ============");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC() : "  (fallo: "+(r!=null?r.getErrorMessage():"null")+")");
  }
  public void run() throws Exception {
    sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm = currentProgram.getFunctionManager();
    dec = new DecompInterface(); dec.openProgram(currentProgram);
    mon = new ConsoleTaskMonitor();
    long[] sites = {0x4000b2eaL, 0x4000c8a4L, 0x400051d4L};
    for (long s: sites) dump(s);
    println("\n[GhidraDsp] fin.");
  }
}
