//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraVer2 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();

  void dump(Function f, String tag) throws Exception {
    if(f==null){println("[!] null func "+tag);return;}
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }

  void refsTo(long va, String label) throws Exception {
    Address sa=sp.getAddress(va);
    println("\n===== refs to "+label+" @ "+sa+" =====");
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sa);
    List<Function> fns=new ArrayList<>();
    int n=0;
    while(it.hasNext()){
      Reference rf=it.next(); Address from=rf.getFromAddress();
      Function f=fm.getFunctionContaining(from);
      println("  "+rf.getReferenceType()+" from "+from+" in "+(f!=null?f.getName():"?"));
      if(f!=null) fns.add(f);
      if(++n>40) break;
    }
    if(n==0) println("  (none)");
    for(Function f:fns) dump(f, "ref-to-"+label);
  }

  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();

    refsTo(0x400a95c0L, "DAT_400a95c0(version_ptr)");

    println("\n[END]");
  }
}
