//@category Octatrack
// Trig-LED capability: FUN_400132c4(id, state) is called by FUN_4004d640 as an LED
// setter. Decompile it and follow what it writes, to learn how many states an LED
// can express (on/off vs intensity vs colour).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraLedDrv extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();

    for(long a: new long[]{0x400132c4L}){
      Function f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn @"+Long.toHexString(a));continue;}
      var r=d.decompileFunction(f,300,mon);
      println("#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }

    // distinct second-argument values passed to FUN_400132c4 -> how many LED states exist?
    println("\n=== call sites of FUN_400132c4 (arg setup) ===");
    Address t=sp.getAddress(0x400132c4L);
    var ri=currentProgram.getReferenceManager().getReferencesTo(t);
    int n=0;
    while(ri.hasNext()){
      var r=ri.next(); if(!r.getReferenceType().isCall()) continue;
      Function f=fm.getFunctionContaining(r.getFromAddress());
      Instruction ins=lst.getInstructionAt(r.getFromAddress());
      List<String> ctx=new ArrayList<>(); Instruction p=ins;
      for(int i=0;i<5&&p!=null;i++){p=p.getPrevious(); if(p!=null) ctx.add(0,p.toString());}
      println("  "+(f!=null?f.getName():"?")+" @"+r.getFromAddress()+" : "+String.join(" ; ",ctx));
      if(++n>45){println("  ...");break;}
    }
    println("  ("+n+" sites)");
    println("\n[END]");
  }
}
