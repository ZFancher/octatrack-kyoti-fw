//@category Octatrack
// Who consumes the 2-bit LED state buffer at 0x460ba98c, and does anything ever
// write state 2 or 3? Also: is FUN_40083eb0 the variable-state (trig?) painter?
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraLedBuf extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();

    println("=== instructions referencing the LED buffer 0x460ba98c ===");
    var it=lst.getInstructions(true);
    Set<String> fns=new TreeSet<>();
    while(it.hasNext()){
      var ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++)
        for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar && ((Scalar)ob).getUnsignedValue()==0x460ba98cL){
            var f=fm.getFunctionContaining(ins.getAddress());
            println("  @"+ins.getAddress()+"  "+ins+"  in "+(f!=null?f.getName():"?"));
            if(f!=null) fns.add(f.getName()+"@"+f.getEntryPoint());
          }
    }
    println("  functions: "+fns);

    for(long a: new long[]{0x40083eb0L}){
      var f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn");continue;}
      var r=d.decompileFunction(f,300,mon);
      println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("\n[END]");
  }
}
