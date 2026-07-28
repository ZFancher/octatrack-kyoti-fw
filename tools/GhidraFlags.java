//@category Octatrack
// Where do the PERSONALIZE flags live? Decompile a few checkbox getters -- if they
// read bits of one word with spare bits, a new option persists for free.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraFlags extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    long[] g={0x40068ce0L,0x40068d38L,0x400688d8L,0x40068de8L,0x40068c68L};
    String[] n={"QUANTIZE LIVE REC","PREVIEW WITHOUT FX","MUTE FOCUSES TRK",
                "DIS. PAGE AUTOCOPY","EXT LEN GRID-REC"};
    for(int i=0;i<g.length;i++){
      var f=fm.getFunctionAt(sp.getAddress(g[i]));
      if(f==null){println("no fn @"+Long.toHexString(g[i]));continue;}
      var r=d.decompileFunction(f,200,mon);
      println("\n#### "+n[i]+"  "+f.getName()+" ("+f.getBody().getNumAddresses()+"B) ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("[END]");
  }
}
