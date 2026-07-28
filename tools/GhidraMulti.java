//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraMulti extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();
  void dump(long s) throws Exception {
    Address a=sp.getAddress(s); Function f=fm.getFunctionContaining(a);
    if(f==null){disassemble(a); f=createFunction(a,null);}
    if(f==null){println("[!] sin func @"+Long.toHexString(s));return;}
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,90,mon);
    String c = r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)";
    if(c.length()>1400) c=c.substring(0,1400)+"...[trunc]";
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" ####");
    println(c);
  }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    dump(0x4000ea2aL); dump(0x4000ef7eL); dump(0x40002df4L); dump(0x40041de2L);
    println("\n[END]");
  }
}
