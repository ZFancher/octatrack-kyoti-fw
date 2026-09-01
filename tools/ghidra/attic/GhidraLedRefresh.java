//@category Octatrack
// The encoder patch updates per_track_part (the audio follows) but the track LED keeps
// its old brightness. Who calls the painter FUN_40083eb0, i.e. what triggers a repaint?
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraLedRefresh extends GhidraScript {
  void callers(long a,String tag,int depth){
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var f=fm.getFunctionAt(sp.getAddress(a));
    println("\n-- llamadores de "+tag+" --");
    if(f==null){println("   (sin funcion)");return;}
    List<String> o=new ArrayList<>();
    for(Function c:f.getCallingFunctions(new ConsoleTaskMonitor()))
      o.add(String.format("   %-16s @%s (%dB)",c.getName(),c.getEntryPoint(),c.getBody().getNumAddresses()));
    Collections.sort(o);
    if(o.isEmpty()) println("   (via tabla / raiz)");
    for(String s:o) println(s);
  }
  public void run() throws Exception {
    callers(0x40083eb0L,"FUN_40083eb0 (pintor de LEDs de track)",1);
    callers(0x40083bf8L,"FUN_40083bf8",1);
    // y el editor de encoder: ¿pide repintado?
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var f=fm.getFunctionAt(sp.getAddress(0x40052e98L));
    var r=d.decompileFunction(f,240,new ConsoleTaskMonitor());
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"";
    println("\n=== llamadas al final de FUN_40052e98 (editor) ===");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("FUN_[0-9a-f]{8}").matcher(c);
    LinkedHashSet<String> s=new LinkedHashSet<>();
    while(m.find()) s.add(m.group());
    println("   "+s);
  }
}
