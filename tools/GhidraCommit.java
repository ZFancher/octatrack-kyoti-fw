// Descompila FUN_40063660 (rutina de OS upgrade) y sus llamadas directas (1 nivel).
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

public class GhidraCommit extends GhidraScript {
    DecompInterface dec;
    ConsoleTaskMonitor mon;

    void dump(Function f) throws Exception {
        DecompileResults res = dec.decompileFunction(f, 120, mon);
        println("\n==================== " + f.getName() + " @ " + f.getEntryPoint()
                + "  (size " + f.getBody().getNumAddresses() + " B) ====================");
        if (res != null && res.decompileCompleted())
            println(res.getDecompiledFunction().getC());
        else
            println("  (fallo decompiler: " + (res != null ? res.getErrorMessage() : "null")
                    + " — leer en ASM)");
    }

    @Override
    public void run() throws Exception {
        AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        FunctionManager fm = currentProgram.getFunctionManager();
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        mon = new ConsoleTaskMonitor();

        Address a = sp.getAddress(0x4007fe80L);
        Function root = fm.getFunctionContaining(a);
        if (root == null) { disassemble(a); root = createFunction(a, "os_flash_commit"); }
        if (root == null) { println("[!] no hay funcion en 0x40063660"); return; }
        try { root.setName("os_flash_commit", ghidra.program.model.symbol.SourceType.USER_DEFINED); } catch (Exception e) {}

        println("################ RAIZ: rutina de OS upgrade ################");
        dump(root);

        println("\n################ LLAMADAS DIRECTAS (1 nivel) ################");
        Set<Function> callees = new LinkedHashSet<>(root.getCalledFunctions(mon));
        println("(" + callees.size() + " funciones llamadas)");
        for (Function c : callees) dump(c);

        println("\n[GhidraCommit] fin.");
    }
}
