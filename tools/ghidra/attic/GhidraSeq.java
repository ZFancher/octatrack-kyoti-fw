//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraSeq extends GhidraScript {
  public void run() throws Exception {
    long[] sites = { 0x40074c22L };  // 'START ALREADY HERE!'
    AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    FunctionManager fm = currentProgram.getFunctionManager();
    DecompInterface dec = new DecompInterface(); dec.openProgram(currentProgram);
    ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    for (long s : sites) {
      Address a = sp.getAddress(s);
      Function f = fm.getFunctionContaining(a);
      if (f == null) { disassemble(a); f = createFunction(a, null); }
      if (f == null) { println("[!] sin funcion @ "+Long.toHexString(s)); continue; }
      DecompileResults r = dec.decompileFunction(f, 120, mon);
      println("\n==================== "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+") ====================");
      println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC() : "  (fallo: "+(r!=null?r.getErrorMessage():"null")+")");
    }
    println("\n[GhidraSeq] fin.");
  }
}
