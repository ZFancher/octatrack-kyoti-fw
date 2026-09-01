//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraScene2 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();

  void dumpAt(long a, String tag) throws Exception {
    Function f=fm.getFunctionContaining(sp.getAddress(a));
    if(f==null){println("[!] no func @"+Long.toHexString(a)+" ("+tag+")");return;}
    if(!seen.add(f.getEntryPoint().getOffset())){println("[dup] "+f.getName()+" ("+tag+")");return;}
    DecompileResults r=dec.decompileFunction(f,150,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }

  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();

    // scene edit menu handlers (reveal scene struct + selected-scene globals)
    dumpAt(0x40062e16L, "menu UNDO/PASTE SCENE");
    dumpAt(0x40062fc8L, "COPY SCENE");
    dumpAt(0x40062f02L, "UNDO/CLEAR SCENE alt");

    println("\n[END]");
  }
}
