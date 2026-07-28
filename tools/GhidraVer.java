//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraVer extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();

  void dump(long s) throws Exception {
    Address a=sp.getAddress(s); Function f=fm.getFunctionContaining(a);
    if(f==null){try{disassemble(a); f=createFunction(a,null);}catch(Exception e){}}
    if(f==null){println("[!] sin func @"+Long.toHexString(s));return;}
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+", via 0x"+Long.toHexString(s)+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }

  // find code refs to a string located at file offset (addr = 0x40000400+off)
  void refsToStr(String label, long fileoff) throws Exception {
    long va = 0x40000400L + fileoff;
    Address sa = sp.getAddress(va);
    println("\n===== refs to "+label+" @ "+sa+" =====");
    ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(sa);
    int n=0;
    while(it.hasNext() && n<20){
      Reference rf=it.next(); Address from=rf.getFromAddress();
      Function f=fm.getFunctionContaining(from);
      println("  from "+from+"  in "+(f!=null?f.getName():"?"));
      n++;
    }
    if(n==0) println("  (no refs — maybe pointer-table indirect)");
  }

  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();

    // pea sites from string map (code addresses)
    dump(0x4003b4aaL);   // uses '%c%d.%d'
    dump(0x4006989aL);   // uses '1.%d.%d' (UI version, SYSTEM STATUS)
    dump(0x40088212L);   // uses 'OS_VERSION'

    // Who references the format/version strings?
    refsToStr("%c%d.%d", 737309);
    refsToStr("1.%d.%d", 745717); // "1.%d.%d" file offset (from dump: after 'GB\0')
    refsToStr("OS_VERSION(proj)", 752416);

    println("\n[END]");
  }
}
