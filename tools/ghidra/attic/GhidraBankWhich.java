// Report the function containing each given address.
//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;

public class GhidraBankWhich extends GhidraScript {
    public void run() throws Exception {
        for (String s : getScriptArgs()) {
            long t = Long.parseLong(s.replace("0x",""),16);
            Address a = toAddr(t);
            Function f = getFunctionContaining(a);
            println(String.format("%08x -> %s", t, f==null?"<none>":(f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses())));
        }
    }
}
