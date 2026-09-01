//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraVoice extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  void dump(long s) throws Exception {
    Address a = sp.getAddress(s);
    Function f = fm.getFunctionContaining(a);
    if (f == null) { disassemble(a); f = createFunction(a, null); }
    if (f == null) { println("[!] sin funcion @ "+Long.toHexString(s)); return; }
    DecompileResults r = dec.decompileFunction(f, 120, mon);
    println("\n============ "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+", contiene 0x"+Long.toHexString(s)+") ============");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC() : "  (fallo: "+(r!=null?r.getErrorMessage():"null")+")");
  }
  public void run() throws Exception {
    sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm = currentProgram.getFunctionManager();
    dec = new DecompInterface(); dec.openProgram(currentProgram);
    mon = new ConsoleTaskMonitor();
    long[] sites = {0x4004554aL, 0x4002f0e8L, 0x400835a8L};
    for (long s: sites) dump(s);
    println("\n[GhidraVoice] fin.");
  }
}
