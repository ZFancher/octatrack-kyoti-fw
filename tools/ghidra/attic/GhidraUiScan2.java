//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiScan2 extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst;
  void dec(long a,int lim){ Function f=fm.getFunctionContaining(sp.getAddress(a)); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####"); println(lim>0&&c.length()>lim?c.substring(0,lim)+"..(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    // find code referencing the scancode map region 0x400abc00..0x400abd40
    println("== instrs referencing scancode map 0x400abc00..0x400abd40 ==");
    Set<Long> fns=new LinkedHashSet<>();
    var it=lst.getInstructions(true);
    while(it.hasNext()){ var ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++) for(Object ob:ins.getOpObjects(o))
        if(ob instanceof Scalar){ long v=((Scalar)ob).getUnsignedValue();
          if(0x400abc00L<=v&&v<0x400abd40L){ var f=fm.getFunctionContaining(ins.getAddress());
            println("  "+ins.getAddress()+"  "+ins+"  in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?"));
            if(f!=null) fns.add(f.getEntryPoint().getOffset()); } } }
    for(long a: fns) dec(a, 3500);
    println("[END]");
  }
}
