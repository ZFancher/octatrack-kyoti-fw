//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraEd3 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();
  void dump(long s) throws Exception {
    Address a=sp.getAddress(s); Function f=fm.getFunctionContaining(a);
    if(f==null){disassemble(a); f=createFunction(a,null);}
    if(f==null){println("[!] @"+Long.toHexString(s));return;}
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,60,mon);
    String c=r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"(no-C)";
    if(c.length()>900)c=c.substring(0,900)+"…";
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" ####\n"+c);
  }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    for(long s: new long[]{0x400422a6L,0x40042880L,0x40042b32L,0x40043012L})dump(s);
    println("[END]");
  }
}
