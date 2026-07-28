//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraLed extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();

    // raw listing of the event loop's scene-display block: which pattern indexes the read?
    println("=== FUN_40061a94 listing 0x40062c20..0x40062ca8 ===");
    var a=sp.getAddress(0x40062c20L); var end=sp.getAddress(0x40062ca8L);
    var ins=lst.getInstructionAt(a);
    while(ins!=null && ins.getAddress().compareTo(end)<=0){
      println("  "+ins.getAddress()+"  "+ins);
      ins=ins.getNext();
    }
    for(long f: new long[]{0x40033e3cL,0x4004d640L,0x4004d948L}){
      Function fn=fm.getFunctionAt(sp.getAddress(f));
      if(fn==null){println("no fn @"+Long.toHexString(f));continue;}
      var r=d.decompileFunction(fn,300,mon);
      println("\n#### "+fn.getName()+" @ "+fn.getEntryPoint()+" size="+fn.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("\n[END]");
  }
}
