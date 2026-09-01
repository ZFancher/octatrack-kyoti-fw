//@category Octatrack
// sanity: is the program analyzed? how many functions? refs working?
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.address.Address;

public class GhidraDiag extends GhidraScript {
    public void run() throws Exception {
        println("program: " + currentProgram.getName());
        println("imageBase: " + currentProgram.getImageBase());
        println("lang: " + currentProgram.getLanguageID() + "  compiler: " + currentProgram.getCompilerSpec().getCompilerSpecID());
        println("--- memory blocks ---");
        for (MemoryBlock b : currentProgram.getMemory().getBlocks())
            println("  " + b.getName() + "  " + b.getStart() + " - " + b.getEnd() + "  " + (b.isExecute()?"X":"-") + (b.isInitialized()?"I":"-"));
        int fc = currentProgram.getFunctionManager().getFunctionCount();
        println("function count: " + fc);
        int i = 0;
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            println("  " + f.getEntryPoint() + "  " + f.getName());
            if (++i >= 15) break;
        }
        long[] probes = { 0x40000400L, 0x40010bc8L, 0x4009b5c8L, 0x400b47d5L };
        for (long p : probes) {
            Address a = toAddr(p);
            Instruction ins = getInstructionAt(a);
            Data d = getDataAt(a);
            println("probe 0x" + Long.toHexString(p) + ": insn=" + (ins!=null?ins.toString():"null")
                + "  data=" + (d!=null?d.toString():"null")
                + "  func=" + (getFunctionContaining(a)!=null?getFunctionContaining(a).getName():"null"));
        }
        // raw bytes at 0x400b47d5
        byte[] buf = new byte[20];
        currentProgram.getMemory().getBytes(toAddr(0x400b47d5L), buf);
        StringBuilder sb = new StringBuilder("bytes @0x400b47d5: ");
        for (byte x : buf) sb.append(String.format("%02x ", x));
        println(sb.toString());
    }
}
