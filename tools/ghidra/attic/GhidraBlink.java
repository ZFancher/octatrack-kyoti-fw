//@category Octatrack
// The dim indicator flickers on dirty tracks only while the sequencer runs. Read the
// stock colour logic of FUN_40083eb0 in full: which bits of _DAT_80000008 gate the
// blink branch, and how the phase counter _DAT_460fab4a drives it.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraBlink extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    var f=fm.getFunctionAt(sp.getAddress(0x40083eb0L));
    var r=d.decompileFunction(f,300,mon);
    println("#### FUN_40083eb0 completo ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    println("\n=== listado crudo del bucle 0x40083ee2..0x40083fa4 ===");
    var it=lst.getInstructions(new AddressSet(sp.getAddress(0x40083ee2L),sp.getAddress(0x40083fa4L)),true);
    while(it.hasNext()){ var i=it.next(); println("  "+i.getAddress()+"  "+i); }
  }
}
