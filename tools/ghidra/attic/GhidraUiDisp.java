//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiDisp extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; Listing lst; ReferenceManager rm;
  Function ensure(long a){ Address ad=sp.getAddress(a); Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} } return f; }
  void dec(long a,int limit){ Function f=ensure(a);
    if(f==null){println("\n#### @0x"+Long.toHexString(a)+": no func ####");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (asked 0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing(); rm=currentProgram.getReferenceManager();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();

    println("===== instructions referencing descriptor table 0x400c0080..0x400c0a00 =====");
    Set<String> fns=new LinkedHashSet<>();
    var it=lst.getInstructions(true);
    while(it.hasNext()){ var ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++) for(Object ob:ins.getOpObjects(o))
        if(ob instanceof Scalar){ long v=((Scalar)ob).getUnsignedValue();
          if(0x400c0080L<=v&&v<0x400c0a00L){ var f=fm.getFunctionContaining(ins.getAddress());
            println("  "+ins.getAddress()+"  "+ins+"  in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?"));
            if(f!=null) fns.add(Long.toHexString(f.getEntryPoint().getOffset())); } } }
    println("  dispatcher candidate funcs: "+fns);

    println("\n===== window opener FUN_40059f8c =====");
    dec(0x40059f8cL, 4000);
    println("\n===== FUN_40059ef0 (desc2 handler) =====");
    dec(0x40059ef0L, 2000);
    println("\n===== candidate key handlers =====");
    for(long a: new long[]{0x4004b970L,0x400491a0L,0x4007d468L,0x4007d320L,0x40055678L,0x40030e6cL,0x40030c60L,0x4005e4c8L,0x4005e25cL,0x4004e954L,0x400568e4L})
      dec(a, 1400);
    println("\n[END]");
  }
}
