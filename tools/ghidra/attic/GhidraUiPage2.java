//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiPage2 extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst;
  Function ensure(long a){ Address ad=sp.getAddress(a); Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} } return f; }
  void dec(long a,int limit){ Function f=ensure(a); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit?c.substring(0,limit)+"..(trunc)":c); }
  void writers(long g){ println("\n== writers of global 0x"+Long.toHexString(g)+" ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(g))){ if(!r.getReferenceType().isWrite())continue;
      Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   W "+r.getFromAddress()+" "+(ins!=null?ins:"")+" in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    println("===== grid-edit + live trig handlers (page-offset usage) =====");
    dec(0x40060b58L, 4000);   // grid-rec trig
    dec(0x400501d8L, 4000);   // live trig
    println("\n===== remaining unclassified handlers =====");
    dec(0x4004348cL, 1200);   // 0x19/0x1a
    dec(0x400418e0L, 2500);   // called by 0x4004348c
    dec(0x400491a0L, 1000);   // 0x33/0x20
    dec(0x40049114L, 1800);   // called by 0x400491a0
    dec(0x40055678L, 3000);   // 0x35
    println("\n[END]");
  }
}
