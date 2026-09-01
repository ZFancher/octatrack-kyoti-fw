//@category Octatrack
// Where is the SELECT-window countdown decremented? (_DAT_460d1e58 / 1e50 / 1e54)
// And raw-disassemble the BANK handler around 0x4007af4c, which has no function.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraCountdown extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();

    println("=== quien toca los campos del temporizador ===");
    long[] want={0x460d1e50L,0x460d1e54L,0x460d1e58L,0x460d1e5cL,0x460d1e60L,0x460d1e4cL};
    Set<String> fns=new LinkedHashSet<>();
    var it=lst.getInstructions(true);
    while(it.hasNext()){
      var ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++)
        for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar){
            long v=((Scalar)ob).getUnsignedValue();
            for(long w:want) if(v==w){
              var f=fm.getFunctionContaining(ins.getAddress());
              println(String.format("  0x%x @%s  %-34s en %s",v,ins.getAddress(),ins,f!=null?f.getName():"?"));
              if(f!=null) fns.add(f.getEntryPoint().getOffset()+"");
            }
          }
    }
    println("\n=== decompilacion de los que decrementan ===");
    for(String s:fns){
      var f=fm.getFunctionAt(sp.getAddress(Long.parseLong(s)));
      if(f.getBody().getNumAddresses()>1200) { println("\n#### "+f.getName()+" (grande, omitido) ####"); continue; }
      var r=d.decompileFunction(f,300,mon);
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("\n=== BANK handler: listado crudo 0x4007af10..0x4007af80 ===");
    var i2=lst.getInstructions(new AddressSet(sp.getAddress(0x4007af10L),sp.getAddress(0x4007af80L)),true);
    while(i2.hasNext()){
      var i=i2.next();
      StringBuilder h=new StringBuilder();
      for(byte b:i.getBytes()) h.append(String.format("%02x",b));
      println(String.format("  %s  %-16s %s", i.getAddress(), h, i));
    }
    println("\n[END]");
  }
}
