//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
public class GhidraStats extends GhidraScript {
  public void run() throws Exception {
    FunctionManager fm = currentProgram.getFunctionManager();
    Listing lst = currentProgram.getListing();
    int nf = fm.getFunctionCount();
    long codeBytes=0; int named=0, fun_prefix=0;
    for (Function f : fm.getFunctions(true)) {
      codeBytes += f.getBody().getNumAddresses();
      String n = f.getName();
      if (n.startsWith("FUN_")) fun_prefix++; else named++;
    }
    long total = currentProgram.getMemory().getSize();
    // instrucciones definidas vs datos
    long insns=0; for (Instruction i: lst.getInstructions(true)) insns++;
    println("=== ESTADISTICAS DE LA IMAGEN COLDFIRE ===");
    println("funciones totales identificadas por Ghidra: "+nf);
    println("  con nombre por defecto FUN_xxxx (sin analizar): "+fun_prefix);
    println("  con nombre asignado (analizadas por nosotros): "+named);
    println("bytes en cuerpos de funcion: "+codeBytes);
    println("instrucciones desensambladas: "+insns);
    println("tamaño total de la imagen: "+total);
    println("[fin]");
  }
}
