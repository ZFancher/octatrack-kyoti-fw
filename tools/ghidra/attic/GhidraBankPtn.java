//@category Octatrack
// BANK/PTN selection mode: who references "SELECT BANK" (0x400b7302),
// "SELECT PATTERN" (0x400b484e) and "SELECT PTN" (0x400b7317)?
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraBankPtn extends GhidraScript {
  public void run() throws Exception {
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    long[] want={0x400b7302L,0x400b484eL,0x400b7317L};
    String[] nm={"SELECT BANK","SELECT PATTERN","SELECT PTN"};
    Set<String> fns=new LinkedHashSet<>();
    var it=lst.getInstructions(true);
    while(it.hasNext()){
      var ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++)
        for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar){
            long v=((Scalar)ob).getUnsignedValue();
            for(int i=0;i<want.length;i++) if(v==want[i]){
              var f=fm.getFunctionContaining(ins.getAddress());
              println("  \""+nm[i]+"\" @"+ins.getAddress()+"  ["+ins+"]  en "+(f!=null?f.getName():"?"));
              if(f!=null) fns.add(f.getName()+"@"+f.getEntryPoint()+"|"+f.getEntryPoint().getOffset());
            }
          }
    }
    println("\n=== funciones ===");
    for(String s:fns) println("  "+s.split("\\|")[0]);
    println("\n=== decompilacion ===");
    for(String s:fns){
      long a=Long.parseLong(s.split("\\|")[1]);
      var f=fm.getFunctionAt(currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a));
      var r=d.decompileFunction(f,300,mon);
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("\n[END]");
  }
}
