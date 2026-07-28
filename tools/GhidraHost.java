//@category Octatrack
// Which candidate enforcement host actually runs periodically?
// Walk callers of FUN_4003f1b4 (crossfader) and FUN_4004d640 (LCD scene numbers)
// upward until we hit a root (task entry / dispatch table).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraHost extends GhidraScript {
  FunctionManager fm; ConsoleTaskMonitor mon;
  void up(Function f,int depth,int max,Set<String> path){
    String id=f.getName()+"@"+f.getEntryPoint();
    String pad="  ".repeat(depth+1);
    if(!path.add(id)){println(pad+id+" (cycle)");return;}
    var cs=f.getCallingFunctions(mon);
    if(cs.isEmpty()){println(pad+"^ "+id+"  <ROOT>");}
    for(Function c:cs){
      println(pad+"^ "+c.getName()+" @"+c.getEntryPoint()+" (size="+c.getBody().getNumAddresses()+")");
      if(depth+1<max) up(c,depth+1,max,path);
    }
    path.remove(id);
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager(); mon=new ConsoleTaskMonitor();
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    long[] t={0x4003f1b4L,0x4004d640L,0x40055f18L};
    String[] n={"FUN_4003f1b4 crossfader","FUN_4004d640 LCD scene numbers","FUN_40055f18 (crossfader caller)"};
    for(int i=0;i<t.length;i++){
      var f=fm.getFunctionAt(sp.getAddress(t[i]));
      println("\n============ "+n[i]+" ============");
      if(f==null){println("  (none)");continue;}
      up(f,0,3,new HashSet<>());
    }
    println("\n[END]");
  }
}
