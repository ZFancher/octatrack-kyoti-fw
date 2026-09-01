//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraScene3 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();

  void dump(Function f, String tag) throws Exception {
    if(f==null)return;
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,150,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }

  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    Listing lst=currentProgram.getListing();

    // scan instructions for scalar operands 0x8ed90 / 0x8ed91 (scene selection field offsets)
    Set<Long> want=new HashSet<>(Arrays.asList(0x8ed90L,0x8ed91L));
    InstructionIterator it=lst.getInstructions(true);
    List<Function> hits=new ArrayList<>();
    LinkedHashSet<String> where=new LinkedHashSet<>();
    while(it.hasNext()){
      Instruction ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++)
        for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar){
            long v=((Scalar)ob).getUnsignedValue();
            if(want.contains(v)){
              Function f=fm.getFunctionContaining(ins.getAddress());
              where.add("0x"+Long.toHexString(v)+" @ "+ins.getAddress()+" ["+ins+"] in "+(f!=null?f.getName():"?"));
              if(f!=null) hits.add(f);
            }
          }
    }
    for(String s:where) println("HIT "+s);
    println("--- decompiling "+hits.size()+" hit functions ---");
    for(Function f:hits) dump(f,"scene-field");
    println("\n[END]");
  }
}
