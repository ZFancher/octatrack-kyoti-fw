// Decompile directory-enumeration / open / stat candidates.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;

public class GhidraFsEnum extends ghidra.app.script.GhidraScript {
    long[] FUNCS = { 0x4007f748L, 0x40016864L, 0x400161ecL, 0x40093980L, 0x40080160L };
    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        for (long fa : FUNCS) {
            Function f = getFunctionAt(toAddr(fa));
            if (f == null) { println("NO FUNC @" + toAddr(fa)); continue; }
            println("\n##### " + f.getName() + " @" + f.getEntryPoint() + " #####");
            DecompileResults dr = dec.decompileFunction(f, 90, monitor);
            if (dr != null && dr.getDecompiledFunction() != null)
                println(dr.getDecompiledFunction().getC());
        }
    }
}
