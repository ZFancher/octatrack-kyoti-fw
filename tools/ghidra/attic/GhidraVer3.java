//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraVer3 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();

  void dump(Function f, String tag) throws Exception {
    if(f==null)return;
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }

  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    Listing lst=currentProgram.getListing();

    // scan all instructions for scalar operands equal to interesting flash addrs
    long[] targets={0x4008L, 0x4000L, 0x4004L};
    Set<Long> want=new HashSet<>(); for(long t:targets) want.add(t);
    InstructionIterator it=lst.getInstructions(true);
    List<Function> hits=new ArrayList<>();
    int scanned=0;
    while(it.hasNext()){
      Instruction ins=it.next(); scanned++;
      int nop=ins.getNumOperands();
      for(int o=0;o<nop;o++){
        Object[] rep=ins.getOpObjects(o);
        for(Object ob:rep){
          if(ob instanceof Scalar){
            long v=((Scalar)ob).getUnsignedValue();
            if(want.contains(v)){
              Function f=fm.getFunctionContaining(ins.getAddress());
              println("SCALAR 0x"+Long.toHexString(v)+" @ "+ins.getAddress()+" ["+ins+"] in "+(f!=null?f.getName():"?"));
              if(f!=null) hits.add(f);
            }
          }
        }
      }
    }
    println("scanned "+scanned+" instructions");
    for(Function f:hits) dump(f,"scalar-flashver");
    println("\n[END]");
  }
}
