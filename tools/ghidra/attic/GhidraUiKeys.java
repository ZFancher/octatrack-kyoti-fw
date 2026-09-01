//@category Octatrack
// Disassemble+decompile the BANK/PTN key handlers and the SELECT BANK window opener,
// and find who references the descriptor tables at 0x400c0086 / 0x400c0666.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiKeys extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; Listing lst; ReferenceManager rm;
  Function ensure(long a){
    Address ad=sp.getAddress(a);
    Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} }
    return f;
  }
  void dec(long a,int limit){
    Function f=ensure(a);
    if(f==null){println("\n#### @0x"+Long.toHexString(a)+" : could not make function ####");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (asked 0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(trunc)":c);
  }
  void raw(long s,long e){
    println("\n=== raw "+Long.toHexString(s)+".."+Long.toHexString(e)+" ===");
    var it=lst.getInstructions(new AddressSet(sp.getAddress(s),sp.getAddress(e)),true);
    while(it.hasNext()){ var i=it.next(); StringBuilder h=new StringBuilder();
      try{for(byte b:i.getBytes())h.append(String.format("%02x",b));}catch(Exception e2){}
      println(String.format("  %s  %-16s %s",i.getAddress(),h,i)); }
  }
  void refsTo(long a){
    println("-- refs to 0x"+Long.toHexString(a)+" --");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){
      Function c=fm.getFunctionContaining(r.getFromAddress());
      var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+r.getFromAddress()+" "+(ins!=null?ins:"(data)")+" ["+r.getReferenceType()+"] in "+(c!=null?c.getName()+" @"+c.getEntryPoint():"<none>"));
    }
  }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing(); rm=currentProgram.getReferenceManager();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();

    println("===== BANK descriptor handler set =====");
    dec(0x4007af80L, 5000);   // handler A (draw?)
    dec(0x4007b3e0L, 3000);   // handler B
    dec(0x4007af24L, 1500);   // handler C (sets 0x460e73c2=1)
    println("\n===== SELECT BANK window opener (0x4007af30, not in a func) =====");
    raw(0x4007af30L,0x4007af7cL);
    dec(0x4007af30L, 3000);

    println("\n===== who references the descriptor tables =====");
    refsTo(0x400c0086L); refsTo(0x400c0080L); refsTo(0x400c0088L);
    refsTo(0x400c0666L); refsTo(0x400c0660L); refsTo(0x400c0668L);

    println("\n===== PTN key handler FUN_4005a044 + its callers =====");
    dec(0x4005a044L, 2500);
    refsTo(0x4005a044L);

    println("\n[END]");
  }
}
