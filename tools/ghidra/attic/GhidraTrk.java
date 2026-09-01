//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraTrk extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  void dump(long s, String label) throws Exception {
    Address a = sp.getAddress(s);
    Function f = fm.getFunctionContaining(a);
    if (f == null) { disassemble(a); f = createFunction(a, label); }
    if (f == null) { println("[!] sin funcion @ "+Long.toHexString(s)); return; }
    DecompileResults r = dec.decompileFunction(f, 120, mon);
    println("\n==================== "+(label!=null?label:f.getName())+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+") ====================");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC() : "  (fallo: "+(r!=null?r.getErrorMessage():"null")+")");
  }
  public void run() throws Exception {
    sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm = currentProgram.getFunctionManager();
    dec = new DecompInterface(); dec.openProgram(currentProgram);
    mon = new ConsoleTaskMonitor();
    dump(0x40000e50L, "track_state_ptr");
    dump(0x4009b290L, "midi_track_check");
    println("\n[GhidraTrk] fin.");
  }
}
