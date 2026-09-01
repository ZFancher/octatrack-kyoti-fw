// Descompila usando las fronteras de funcion que Ghidra descubrio (getFunctionContaining),
// en vez de forzar prologos. Arregla los errores de varnodes de la primera pasada.
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

public class GhidraDecompile2 extends GhidraScript {
    @Override
    public void run() throws Exception {
        // direcciones DE CODIGO (sitios de pea/call) o de funcion conocida.
        long[] sites = {
            0x4006d57cL,  // el despachador de UI compartido (decompilar directo)
            0x40086da2L,  // dentro de la serializacion de ajustes del proyecto
            0x400867e0L,  // seccion [SAMPLE] del project file
            0x400636eeL,  // ruta 'OS UPGRADE'
            0x40022fdcL,  // 'COLLECT SAMPLES'
        };
        AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        FunctionManager fm = currentProgram.getFunctionManager();
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();

        LinkedHashSet<Address> entries = new LinkedHashSet<>();
        for (long s : sites) {
            Address a = sp.getAddress(s);
            Function f = fm.getFunctionContaining(a);
            if (f == null) {          // no descubierta: intenta crearla ahi
                try { disassemble(a); f = createFunction(a, null); } catch (Exception e) {}
            }
            if (f != null) entries.add(f.getEntryPoint());
            else println("[!] sin funcion para 0x" + Long.toHexString(s));
        }

        for (Address entry : entries) {
            Function f = fm.getFunctionAt(entry);
            DecompileResults res = dec.decompileFunction(f, 120, mon);
            println("\n==================== " + f.getName() + " @ " + entry
                    + "  (size " + f.getBody().getNumAddresses() + " B) ====================");
            if (res != null && res.decompileCompleted())
                println(res.getDecompiledFunction().getC());
            else
                println("  (fallo: " + (res != null ? res.getErrorMessage() : "null") + ")");
        }
        println("\n[GhidraDecompile2] fin.");
    }
}
