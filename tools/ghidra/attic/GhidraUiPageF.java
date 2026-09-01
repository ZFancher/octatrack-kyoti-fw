//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiPageF extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager(); var rm=currentProgram.getReferenceManager();
    var lst=currentProgram.getListing(); Memory mem=currentProgram.getMemory();
    var d=new DecompInterface(); d.openProgram(currentProgram); var mon=new ConsoleTaskMonitor();
    println("== callers of FUN_40048890 (page-advance shim) ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(0x40048890L))){ var c=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+r.getFromAddress()+" "+(ins!=null?ins:"")+" ["+r.getReferenceType()+"] in "+(c!=null?c.getName()+" @"+c.getEntryPoint():"?")); }
    println("\n== FUN_40048890 ==");
    var f=fm.getFunctionContaining(sp.getAddress(0x40048890L)); var rr=d.decompileFunction(f,120,mon);
    println(rr.getDecompiledFunction().getC());
    println("== 0x1b record bytes @0x400c0602 (26 bytes) ==");
    StringBuilder sb=new StringBuilder();
    for(long a=0x400c0602L;a<0x400c0602L+0x1a;a++){ sb.append(String.format("%02x",mem.getByte(sp.getAddress(a))&0xff)); if((a&1)==1)sb.append(' ');}
    println("   "+sb);
    println("[END]");
  }
}
