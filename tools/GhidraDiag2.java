//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraDiag2 extends GhidraScript {
    public void run() throws Exception {
        ReferenceManager rm = currentProgram.getReferenceManager();
        FunctionManager fm = currentProgram.getFunctionManager();

        // 1. does reference analysis exist at all?
        long total = 0;
        for (Address a : rm.getReferenceSourceIterator(currentProgram.getMinAddress(), true)) {
            total++;
            if (total > 200000) break;
        }
        println("total reference source addresses (capped 200k): " + total);

        // 2. callers of FUN_40010bc8 via getCallingFunctions
        Function mf = fm.getFunctionAt(toAddr(0x40010bc8L));
        println("\nFUN_40010bc8 calling functions:");
        for (Function c : mf.getCallingFunctions(new ConsoleTaskMonitor()))
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
        println("FUN_40010bc8 getReferenceIteratorTo count:");
        int rc = 0; for (Reference r : rm.getReferencesTo(toAddr(0x40010bc8L))) rc++;
        println("  " + rc);

        // 3. raw scan the image for BE32 pointer to the "MIDI LFO SETUP" string 0x400b47d5
        Memory mem = currentProgram.getMemory();
        Address base = toAddr(0x40000400L);
        long len = 0x4010fdefL - 0x40000400L;
        byte[] img = new byte[(int) len];
        mem.getBytes(base, img);
        long[] needles = { 0x400b47d5L, 0x400b47c0L, 0x400b47d0L };
        for (long ndl : needles) {
            println("\nBE32 occurrences of 0x" + Long.toHexString(ndl) + ":");
            int found = 0;
            for (int i = 0; i + 4 <= img.length; i++) {
                long v = ((img[i] & 0xffL) << 24) | ((img[i+1] & 0xffL) << 16) | ((img[i+2] & 0xffL) << 8) | (img[i+3] & 0xffL);
                if (v == ndl) {
                    long va = 0x40000400L + i;
                    Address at = toAddr(va);
                    Function cf = getFunctionContaining(at);
                    Instruction ins = getInstructionAt(at);
                    println("  @0x" + Long.toHexString(va) + (cf != null ? " in " + cf.getName() : "")
                        + (ins != null ? "  [" + ins + "]" : "  (data/immediate)"));
                    if (++found > 20) break;
                }
            }
            if (found == 0) println("  none");
        }

        // 4. Look at what's right before the string cluster: dump 0x400b47a0..0x400b4820 as both
        println("\nbytes 0x400b4780..0x400b4830:");
        byte[] b = new byte[0xb0];
        mem.getBytes(toAddr(0x400b4780L), b);
        for (int r = 0; r < b.length; r += 16) {
            StringBuilder h = new StringBuilder(String.format("  %08x  ", 0x400b4780L + r));
            StringBuilder a = new StringBuilder();
            for (int c = 0; c < 16 && r + c < b.length; c++) {
                h.append(String.format("%02x ", b[r+c]));
                char ch = (char)(b[r+c] & 0xff);
                a.append(ch >= 32 && ch < 127 ? ch : '.');
            }
            println(h + " " + a);
        }
    }
}
