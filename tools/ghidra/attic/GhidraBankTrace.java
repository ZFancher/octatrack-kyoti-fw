// Trace the RELOAD BANK confirm handler chain from LAB_40063bf8 down to CF read.
//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.LinkedHashSet;
import java.util.Set;

public class GhidraBankTrace extends GhidraScript {
    DecompInterface dec;
    ConsoleTaskMonitor mon;
    AddressSpace sp;
    FunctionManager fm;

    void dump(Function f) throws Exception {
        DecompileResults res = dec.decompileFunction(f, 120, mon);
        println("\n==================== " + f.getName() + " @ " + f.getEntryPoint()
                + "  (size " + f.getBody().getNumAddresses() + " B) ====================");
        if (res != null && res.decompileCompleted())
            println(res.getDecompiledFunction().getC());
        else
            println("  (decompiler failed: " + (res != null ? res.getErrorMessage() : "null") + ")");
    }

    Function fnAt(long addr) {
        Address a = sp.getAddress(addr);
        Function f = fm.getFunctionContaining(a);
        if (f == null) {
            try { disassemble(a); f = createFunction(a, null); } catch (Exception e) {}
        }
        return f;
    }

    @Override
    public void run() throws Exception {
        sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        fm = currentProgram.getFunctionManager();
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        mon = new ConsoleTaskMonitor();

        // Parse addresses from script args
        String[] args = getScriptArgs();
        long[] targets;
        if (args != null && args.length > 0) {
            targets = new long[args.length];
            for (int i = 0; i < args.length; i++) targets[i] = Long.parseLong(args[i].replace("0x",""), 16);
        } else {
            targets = new long[]{ 0x40063bf8L, 0x40063590L };
        }
        for (long t : targets) {
            Function f = fnAt(t);
            if (f == null) { println("[!] no function at " + Long.toHexString(t)); continue; }
            println("################ ROOT for " + Long.toHexString(t) + " => " + f.getName() + " ################");
            dump(f);
            println("\n---------------- DIRECT CALLEES of " + f.getName() + " ----------------");
            Set<Function> callees = new LinkedHashSet<>(f.getCalledFunctions(mon));
            for (Function c : callees) dump(c);
        }
        println("\n[GhidraBankTrace] done.");
    }
}
