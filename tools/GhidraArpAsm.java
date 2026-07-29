// Dump disassembly listing for scale clamp (FUN_4007a2ec) and formatter guard (FUN_4003b790).
// @category Octatrack
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;

public class GhidraArpAsm extends ghidra.app.script.GhidraScript {
    void listRange(long start, long end) {
        Listing lst = currentProgram.getListing();
        InstructionIterator it = lst.getInstructions(toAddr(start), true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            if (ins.getAddress().getOffset() > end) break;
            StringBuilder sb = new StringBuilder();
            sb.append(ins.getAddress()).append(":  ").append(ins.toString());
            println(sb.toString());
        }
    }
    public void run() throws Exception {
        println("===== FUN_4007a2ec scale-clamp region (param_1==5) 0x4007a3e0..0x4007a4b0 =====");
        listRange(0x4007a3e0L, 0x4007a4b0L);
        println("\n===== FUN_4003b790 formatter 0x4003b790..0x4003b7d8 =====");
        listRange(0x4003b790L, 0x4003b7d8L);
    }
}
