import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
public class GhidraClose extends ghidra.app.script.GhidraScript {
  public void run() throws Exception {
    DecompInterface dec=new DecompInterface(); dec.openProgram(currentProgram);
    // decompile the cancel handler FUN_4006d4a8 and find who clears _DAT_460e5cd0
    for (long fa: new long[]{0x4006d47cL}) {
      Function f=getFunctionAt(toAddr(fa));
      println("#### "+f.getName()+" ####");
      DecompileResults dr=dec.decompileFunction(f,60,monitor);
      if(dr!=null&&dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());
    }
    // find code that WRITES to 0x460e5cd0 (the modal handle) -> the close path
    println("=== writers/refs to 0x460e5cd0 (modal handle) ===");
    ReferenceManager rm=currentProgram.getReferenceManager();
    for (Reference r: rm.getReferencesTo(toAddr(0x460e5cd0L))) {
      Function f=getFunctionContaining(r.getFromAddress());
      println("  "+r.getReferenceType()+" @"+r.getFromAddress()+" in "+(f==null?"?":f.getName()));
    }
  }
}
