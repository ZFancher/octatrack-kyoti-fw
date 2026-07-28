//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraScene6 extends GhidraScript {
  public void run()throws Exception{
    AddressSpace sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    Listing lst=currentProgram.getListing();
    FunctionManager fm=currentProgram.getFunctionManager();
    long[] fns={0x4003f1b4L, 0x40052944L, 0x40009094L};
    for(long fa:fns){
      Function f=fm.getFunctionContaining(sp.getAddress(fa));
      println("\n===== "+f.getName()+" @ "+f.getEntryPoint()+" (raw listing) =====");
      InstructionIterator it=lst.getInstructions(f.getBody(),true);
      int n=0;
      while(it.hasNext() && n<70){ Instruction ins=it.next(); n++;
        println(ins.getAddress()+":  "+ins.toString());
      }
    }
    println("\n[END]");
  }
}
