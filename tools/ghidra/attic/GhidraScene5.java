//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
public class GhidraScene5 extends GhidraScript {
  public void run()throws Exception{
    AddressSpace sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    Listing lst=currentProgram.getListing();
    // dump every instruction in FUN_40061a94 that mentions 0x80000002 or 0x80000003 (as scalar or ref)
    long[] fns={0x40061a94L};
    FunctionManager fm=currentProgram.getFunctionManager();
    for(long fa:fns){
      Function f=fm.getFunctionContaining(sp.getAddress(fa));
      println("=== "+f.getName()+" @ "+f.getEntryPoint()+" ===");
      InstructionIterator it=lst.getInstructions(f.getBody(),true);
      while(it.hasNext()){
        Instruction ins=it.next();
        boolean hit=false;
        // check refs
        for(Reference r:ins.getReferencesFrom()){
          long to=r.getToAddress().getOffset();
          if(to==0x80000002L||to==0x80000003L){ hit=true;
            println((r.getReferenceType().isWrite()?"[W] ":"[R] ")+ins.getAddress()+"  "+ins+"   -> 0x"+Long.toHexString(to)); }
        }
        // also scalar operands
        if(!hit) for(int o=0;o<ins.getNumOperands();o++) for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar){ long v=((Scalar)ob).getUnsignedValue();
            if(v==0x80000002L||v==0x80000003L) println("[S] "+ins.getAddress()+"  "+ins+"   scalar 0x"+Long.toHexString(v)); }
      }
    }
    println("[END]");
  }
}
