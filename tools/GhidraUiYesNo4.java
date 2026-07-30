//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiYesNo4 extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst; Memory mem;
  void refs(long a,String tag){ println("\n== refs to "+tag+" 0x"+Long.toHexString(a)+" ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){ Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+(r.getReferenceType().isWrite()?"W":"r/c")+" "+r.getFromAddress()+" "+(ins!=null?ins:"")+" in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  void dec(long a,int lim){ Function f=fm.getFunctionContaining(sp.getAddress(a)); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####"); println(lim>0&&c.length()>lim?c.substring(0,lim)+"..(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing(); mem=currentProgram.getMemory();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    println("===== YES/NO candidates =====");
    dec(0x4004ecfcL,4000);   // keycodes 0x38-0x3e (7 keys)
    dec(0x40058f34L,2500);   // context keymap 0x00-0x06 @0x400c09ba
    dec(0x40055008L,2500);   // context keymap 0x00-0x06 @0x400c0858
    println("\n===== project load complete: projname[0] writer FUN_40025848 =====");
    dec(0x40025848L,2000);
    refs(0x40025848L,"FUN_40025848");
    println("\n===== FUN_40023cf8 (writes projname area) + FUN_400255ec (is-active test) =====");
    dec(0x40023cf8L,2500);
    println("[END]");
  }
}
