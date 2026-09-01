//@category Octatrack
// The timed SELECT window: FUN_40059f8c(text, ticks, ?, on_timeout).
// Decompile it, the timeout callback FUN_40043418, and the function that owns the
// "SELECT BANK" reference at 0x4007af4c (Ghidra has no function there yet).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraTimeout extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; FunctionManager fm;
  void dump(long a,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    if(f==null) f=fm.getFunctionContaining(sp.getAddress(a));
    if(f==null){println("\n#### sin funcion en 0x"+Long.toHexString(a)+" ("+tag+") ####");return;}
    var r=d.decompileFunction(f,300,mon);
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    dump(0x40059f8cL,"ventana temporizada");
    dump(0x40043418L,"callback de expiracion");
    dump(0x4007af4cL,"quien muestra SELECT BANK");
    // otros llamadores de la ventana temporizada -> donde mas hay timers
    println("\n=== llamadores de FUN_40059f8c ===");
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    var ri=currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(0x40059f8cL));
    while(ri.hasNext()){
      var r=ri.next(); if(!r.getReferenceType().isCall()) continue;
      var f=fm.getFunctionContaining(r.getFromAddress());
      var ins=lst.getInstructionAt(r.getFromAddress());
      List<String> ctx=new ArrayList<>(); var p=ins;
      for(int i=0;i<5&&p!=null;i++){p=p.getPrevious(); if(p!=null) ctx.add(0,p.toString());}
      println("  "+(f!=null?f.getName():"?")+" @"+r.getFromAddress()+" : "+String.join(" ; ",ctx));
    }
    println("\n[END]");
  }
}
