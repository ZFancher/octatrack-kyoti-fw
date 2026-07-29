//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiTrig extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm;
  Function ensure(long a){ Address ad=sp.getAddress(a); Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} } return f; }
  void dec(long a,int limit){ Function f=ensure(a);
    if(f==null){println("\n#### @0x"+Long.toHexString(a)+": no func ####");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    println("===== TRIG key handler 0x40060ce0 (bank/pattern pick while window open) =====");
    dec(0x40060ce0L, 7000);
    println("\n===== TRACK key handler 0x40040250 =====");
    dec(0x40040250L, 2500);
    println("\n===== single-key PAGE candidates =====");
    for(long a: new long[]{0x40058ab8L,0x4004ffc4L,0x4004348cL,0x4004aca4L,0x40061778L,0x40048774L,0x4004e978L,0x40064d78L,0x4006e274L})
      dec(a, 1600);
    println("\n[END]");
  }
}
