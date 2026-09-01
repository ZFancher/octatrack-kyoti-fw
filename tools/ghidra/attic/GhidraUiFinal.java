//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiFinal extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm;
  Function ensure(long a){ Address ad=sp.getAddress(a); Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} } return f; }
  void dec(long a,int limit){ Function f=ensure(a); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,120,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit?c.substring(0,limit)+"..(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    dec(0x400135b0L,1200);       // LED set-level (id, level)
    dec(0x400131a0L,800);        // LED on(id)
    dec(0x400131c8L,800);        // LED off(id)
    dec(0x4006d4a8L,2500);       // confirm popup key handler (YES/NO -> callback)
    println("\n[END]");
  }
}
