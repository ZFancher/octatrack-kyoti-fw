// Print prologue/epilogue of FUN_4009f794 and scan the whole function for any
// branch/jump whose target lands inside the decode block 0x4009fad2..0x4009faff.
// @category Octatrack
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class GhidraArpProlog extends ghidra.app.script.GhidraScript {
    public void run() throws Exception {
        Function f = getFunctionAt(toAddr(0x4009f794L));
        Listing lst = currentProgram.getListing();
        println("=== prologue ===");
        InstructionIterator it = lst.getInstructions(f.getBody(), true);
        int n = 0;
        while (it.hasNext() && n < 6) { println("  " + fmt(it.next())); n++; }
        println("=== epilogue (last 6) ===");
        java.util.ArrayList<Instruction> all = new java.util.ArrayList<>();
        for (Instruction i : lst.getInstructions(f.getBody(), true)) all.add(i);
        for (int i = Math.max(0, all.size() - 6); i < all.size(); i++) println("  " + fmt(all.get(i)));

        println("=== branches/refs into decode block 0x4009fad2..0x4009faff ===");
        long lo = 0x4009fad2L, hi = 0x4009fb00L;
        boolean any = false;
        for (Instruction ins : all) {
            for (Reference r : ins.getReferencesFrom()) {
                if (r.getReferenceType().isFlow()) {
                    long t = r.getToAddress().getOffset();
                    if (t >= lo && t < hi) {
                        println("  " + ins.getAddress() + " -> " + r.getToAddress() + "  (" + ins + ")");
                        any = true;
                    }
                }
            }
        }
        if (!any) println("  NONE (block only entered by fall-through at 0x4009fad2)");
    }
    String fmt(Instruction ins) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : ins.getBytes()) b.append(String.format("%02x", x));
        return String.format("%s  %-16s  %s", ins.getAddress(), b, ins.toString());
    }
}
