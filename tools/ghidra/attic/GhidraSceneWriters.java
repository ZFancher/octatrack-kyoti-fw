//@category Octatrack
// Classify every writer of the LIVE scene selection (0x100a4ede/edf) by walking
// its callers upward, to see whether the sequencer/trig path can touch it.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraSceneWriters extends GhidraScript {
  FunctionManager fm; ConsoleTaskMonitor mon;

  void up(Function f, int depth, int max, Set<String> path) {
    String id = f.getName() + "@" + f.getEntryPoint();
    String pad = "  ".repeat(depth + 1);
    if (!path.add(id)) { println(pad + id + "  (cycle)"); return; }
    Set<Function> cs = f.getCallingFunctions(mon);
    if (cs.isEmpty()) { println(pad + "^ " + id + "  <ROOT / called via table>"); }
    for (Function c : cs) {
      println(pad + "^ " + c.getName() + " @ " + c.getEntryPoint() + " (size=" + c.getBody().getNumAddresses() + ")");
      if (depth + 1 < max) up(c, depth + 1, max, path);
    }
    path.remove(id);
  }

  public void run() throws Exception {
    fm = currentProgram.getFunctionManager();
    mon = new ConsoleTaskMonitor();
    var sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    long[] writers = {0x4000e79cL, 0x4004a100L, 0x40052944L, 0x400a0734L};
    String[] tags = {"FUN_4000e79c (bulk/init?)", "FUN_4004a100 (loader A)",
                     "FUN_40052944 (manual scene assign)", "FUN_400a0734 (loader B)"};
    for (int i = 0; i < writers.length; i++) {
      Function f = fm.getFunctionAt(sp.getAddress(writers[i]));
      println("\n================ " + tags[i] + " ================");
      if (f == null) { println("  (no function)"); continue; }
      up(f, 0, 3, new HashSet<>());
    }
    println("\n[END]");
  }
}
