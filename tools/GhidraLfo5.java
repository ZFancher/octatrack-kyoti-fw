//@category Octatrack
// Find the audio-CC-OUT transmit routine.
//  (a) BE32-scan for pointer to "AUDIO CC OUT" label 0x400b623d and "INT+EXT" 0x400b61ff,
//      decompile referencing funcs (menu handler -> tells us the setting byte).
//  (b) Disasm-scan the whole image for a `jsr`/`bsr` preceded within 6 insns by an
//      immediate 0x1c / 0x1b (CC base for LFO block) -> candidate CC transmitters.
//  (c) dump FUN_40010bc8 wrappers that take (status,data1,data2)-ish args.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo5 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();

    List<Long> be32(long needle) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i + 4 <= img.length; i++) {
            long v = ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL);
            if (v == needle) out.add(base + i);
        }
        return out;
    }
    void dump(Function f) throws Exception {
        if (f == null || !done.add(f.getEntryPoint().getOffset())) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 160, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void scanDump(String label, long ndl) throws Exception {
        println("\n==== BE32 refs to " + label + " 0x" + Long.toHexString(ndl) + " ====");
        for (long va : be32(ndl)) {
            Address at = toAddr(va);
            Function cf = getFunctionContaining(at);
            println("  @0x" + Long.toHexString(va) + (cf != null ? "  in " + cf.getName() : "  (no func)"));
        }
        for (long va : be32(ndl)) {
            Function cf = getFunctionContaining(toAddr(va));
            if (cf != null) dump(cf);
        }
    }

    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);

        scanDump("\"AUDIO CC OUT\"", 0x400b623dL);
        scanDump("\"AUDIO CC IN\"",  0x400b6231L);

        // (b) jsr/bsr with a recent immediate 0x1c or 0x1b
        println("\n==== jsr/bsr preceded by #0x1c/#0x1b within 6 insns ====");
        Listing lst = currentProgram.getListing();
        InstructionIterator it = lst.getInstructions(true);
        ArrayDeque<String> recent = new ArrayDeque<>();
        Address lastImm = null; int sinceImm = 99;
        Set<Long> reported = new HashSet<>();
        while (it.hasNext()) {
            Instruction ins = it.next();
            String s = ins.toString();
            String m = ins.getMnemonicString().toLowerCase();
            if (s.contains("#0x1c") || s.contains("#0x1b") || s.contains("#0x1d")) { lastImm = ins.getAddress(); sinceImm = 0; }
            else sinceImm++;
            if ((m.startsWith("jsr") || m.startsWith("bsr")) && sinceImm <= 6 && lastImm != null) {
                Function cf = getFunctionContaining(ins.getAddress());
                long key = cf != null ? cf.getEntryPoint().getOffset() : ins.getAddress().getOffset();
                if (reported.add(key)) {
                    Address[] fl = ins.getFlows();
                    println("  " + ins.getAddress() + "  " + s + "   in " + (cf != null ? cf.getName() : "?")
                        + "   (imm @" + lastImm + ")"
                        + (fl != null && fl.length > 0 ? " -> " + fl[0] : ""));
                }
            }
        }
        println("\nDone.");
    }
}
