import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Checks that every net/minecraft* member referenced by the built jar exists in the production (SRG) classes. */
public class RefCheck {
    public static void main(String[] args) throws Exception {
        var urls = new ArrayList<URL>();
        for (var p : args[1].split(":")) if (!p.isEmpty()) urls.add(Path.of(p).toUri().toURL());
        var loader = new URLClassLoader(urls.toArray(URL[]::new), null);
        var refs = new TreeSet<String>(Files.readAllLines(Path.of(args[0])));
        var pattern = Pattern.compile("(Method|Field|InterfaceMethod) (net/minecraft[^.]*)\\.(\"?<?[\\w$]+>?\"?):(.*)");
        int bad = 0;
        for (var ref : refs) {
            var m = pattern.matcher(ref);
            if (!m.matches()) continue;
            var owner = m.group(2).replace('/', '.');
            var name = m.group(3).replace("\"", "");
            var desc = m.group(4);
            Class<?> c;
            try { c = Class.forName(owner, false, loader); } catch (Throwable t) { System.out.println("MISSING CLASS " + owner); bad++; continue; }
            boolean found = false;
            for (var k = c; k != null && !found; k = k.getSuperclass()) found = has(k, m.group(1), name, desc);
            if (!found) for (var i : all(c)) if (has(i, m.group(1), name, desc)) { found = true; break; }
            if (!found) { System.out.println("MISSING " + ref); bad++; }
        }
        System.out.println(bad == 0 ? "all " + refs.size() + " references resolve" : bad + " unresolved");
    }
    static Set<Class<?>> all(Class<?> c) { var s = new LinkedHashSet<Class<?>>(); for (var k = c; k != null; k = k.getSuperclass()) for (var i : k.getInterfaces()) { s.add(i); s.addAll(all(i)); } return s; }
    static boolean has(Class<?> k, String kind, String name, String desc) {
        try {
            if (kind.equals("Field")) { for (var f : k.getDeclaredFields()) if (f.getName().equals(name)) return true; return false; }
            if (name.equals("<init>")) { for (var ct : k.getDeclaredConstructors()) if (("(" + d(ct.getParameterTypes()) + ")V").equals(desc)) return true; return false; }
            for (var mt : k.getDeclaredMethods()) if (mt.getName().equals(name) && ("(" + d(mt.getParameterTypes()) + ")" + d(mt.getReturnType())).equals(desc)) return true;
        } catch (Throwable t) { System.out.println("  (reflect fail " + k + ": " + t + ")"); }
        return false;
    }
    static String d(Class<?>... ts) { var sb = new StringBuilder(); for (var t : ts) sb.append(t.isArray() ? t.getName().replace('.', '/') : t.isPrimitive() ? switch (t.getName()) { case "int" -> "I"; case "boolean" -> "Z"; case "void" -> "V"; case "long" -> "J"; case "byte" -> "B"; case "short" -> "S"; case "char" -> "C"; case "float" -> "F"; default -> "D"; } : "L" + t.getName().replace('.', '/') + ";"); return sb.toString(); }
}
