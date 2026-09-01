//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraAB extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();
  void dump(long s) throws Exception {
    Address a=sp.getAddress(s);
    Function f=fm.getFunctionContaining(a);
    if(f==null){disassemble(a); f=createFunction(a,null);}
    if(f==null){println("[!] sin func @"+Long.toHexString(s));return;}
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,90,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+", via 0x"+Long.toHexString(s)+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    dump(0x40005030L);   // trig helper (A/B)
    dump(0x40003d1eL);   // candidato editor de parámetros (GUI)
    println("\n[END]");
  }
}
