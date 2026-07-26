package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;

/**
 * Lowers a compiled {@link Tdfa} to a specialized Java class via in-memory source-code emission
 * + {@code javax.tools.JavaCompiler}. Loaded via a private ClassLoader.
 *
 * Why source-code emission instead of ASM bytecode? {@code COMPUTE_FRAMES} chokes on our
 * cascading-IF state dispatch; writing StackMapTable frames by hand is error-prone and the JIT
 * output from a HotSpot compile of generated source is identical to hand-emitted bytecode for
 * our shapes (per-state switch, cascading range checks). Compile cost is ~50-150 ms per regex,
 * amortized over many matches.
 *
 * Generated source shape (one method per regex):
 * <pre>
 * public final class Gen_N implements Regex.Engine {
 *   public boolean matches(CharSequence i) { return run(i,0,i.length(),true)!=null; }
 *   public boolean find(CharSequence i)    { return run(i,0,i.length(),false)!=null; }
 *   public MatchResult match(CharSequence i, int from) { ... }
 *   private TdfaRunner.MatchHolder run(CharSequence input, int from, int to, boolean anchored) {
 *     int[] regs = new int[N]; Arrays.fill(regs, -1);
 *     int state = 0, pos = from, ...;
 *     while (pos <= to) {
 *       switch (state) { case 0: ...; default: return_or_break; }
 *       ...
 *     }
 *     ...
 *   }
 * }
 * </pre>
 */
public final class TdfaAsmBackend {

    public static Regex.Engine compile(Tdfa tdfa) {
        String src = generateSource(tdfa);
        try {
            Class<?> cls = compileAndLoad(src, tdfa);
            return (Regex.Engine) cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("source backend failed: " + e, e);
        }
    }

    private static String generateSource(Tdfa tdfa) {
        StringBuilder sb = new StringBuilder();
        sb.append("package gen;\n");
        sb.append("import io.github.jemmix.tdfa.Regex;\n");
        sb.append("import io.github.jemmix.tdfa.tdfa.TdfaRunner;\n");
        sb.append("import io.github.jemmix.tdfa.tnfa.Tnfa;\n");
        sb.append("import io.github.jemmix.tdfa.vm.MatchResult;\n");
        sb.append("import java.util.Arrays;\n\n");
        sb.append("public final class Gen_").append(Math.abs(System.identityHashCode(tdfa)))
          .append(" implements Regex.Engine {\n");
        sb.append("  public boolean matches(CharSequence i) { return run(i,0,i.length(),true)!=null; }\n");
        sb.append("  public boolean find(CharSequence i) {\n");
        sb.append("    int len = i.length();\n");
        // Start-state entry mask dictates whether find() can begin at positions > 0.
        if (tdfa.startRequiresBeginText()) {
            sb.append("    TdfaRunner.MatchHolder h = run(i, 0, len, false);\n");
            sb.append("    return h != null;\n");
        } else {
            sb.append("    for (int from = 0; from <= len; from++) {\n");
            sb.append("      TdfaRunner.MatchHolder h = run(i, from, len, false);\n");
            sb.append("      if (h != null) return true;\n");
            sb.append("    }\n");
            sb.append("    return false;\n");
        }
        sb.append("  }\n");
        sb.append("  public MatchResult match(CharSequence i, int from) {\n");
        sb.append("    int len = i.length();\n");
        sb.append("    int maxStart = ").append(tdfa.startRequiresBeginText() ? "0" : "len").append(";\n");
        sb.append("    for (int f = from; f <= maxStart; f++) {\n");
        sb.append("      TdfaRunner.MatchHolder h = run(i, f, len, false);\n");
        sb.append("      if (h != null) return new MatchResult(h.regs, ").append(tdfa.tagCount)
          .append(", ").append(tdfa.groupCount).append(", h.matchStart, h.matchEnd);\n");
        sb.append("    }\n");
        sb.append("    return null;\n");
        sb.append("  }\n");
        sb.append("  private TdfaRunner.MatchHolder run(CharSequence input, int from, int to, boolean anchored) {\n");
        sb.append("    int[] regs = new int[").append(tdfa.registerCount).append("];\n");
        sb.append("    Arrays.fill(regs, -1);\n");
        sb.append("    int state = 0, pos = from;\n");
        sb.append("    int lastAcceptPos = -1, lastAcceptState = -1, matchStart = from;\n");
        sb.append("    boolean haveAccept = false, dead = false;\n");
        sb.append("    if (!entryOk(state, pos, to, input)) return null;\n");
        sb.append("    while (pos <= to) {\n");
        // accept check — A2 lazy snapshot: just record state+pos, no clone yet
        sb.append("      if (isAccept(state) && acceptOk(state, pos, to, input)) { lastAcceptPos = pos; lastAcceptState = state; haveAccept = true; }\n");
        sb.append("      if (pos == to) { break; }\n");
        sb.append("      char c = input.charAt(pos);\n");
        sb.append("      int posFlags = positionFlags(pos, to, input);\n");
        sb.append("      switch (state) {\n");

        int nStates = tdfa.stateCount;
        int[] stateMeta = tdfa.stateMeta;
        int[] stateFinalOpsOff = tdfa.stateFinalOpsOff;
        int[] ranges = tdfa.ranges;
        int[] ops = tdfa.ops;
        for (int s = 0; s < nStates; s++) {
            sb.append("        case ").append(s).append(": {\n");
            int meta = stateMeta[s];
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            for (int i = 0; i < count; i++) {
                int o = (base + i) * 5;
                int lo = ranges[o];
                int hi = ranges[o + 1];
                int target = ranges[o + 2];
                int opsOff = ranges[o + 3];
                int requiredMask = ranges[o + 4];
                if (target < 0) continue;
                sb.append("          if (c >= ").append(lo).append(" && c <= ").append(hi);
                if (requiredMask != 0) sb.append(" && (posFlags & ").append(requiredMask).append(") == ").append(requiredMask);
                sb.append(") {\n");
                // emit ops inline from ops[opsOff..] until OP_END
                if (opsOff != 0) {
                    int j = opsOff;
                    while (ops[j] != Tdfa.OP_END) {
                        int op = ops[j], dst = ops[j + 1], src = ops[j + 2];
                        sb.append("            ");
                        switch (op) {
                            case Tdfa.OP_SET_POS: sb.append("regs[").append(dst).append("] = pos;\n"); break;
                            case Tdfa.OP_SET_NIL: sb.append("regs[").append(dst).append("] = -1;\n"); break;
                            case Tdfa.OP_COPY:    sb.append("regs[").append(dst).append("] = regs[").append(src).append("];\n"); break;
                        }
                        j += 3;
                    }
                }
                sb.append("            state = ").append(target).append(";\n");
                sb.append("            if (!entryOk(state, pos + 1, to, input)) { dead = true; break; }\n");
                sb.append("            pos++; continue;\n");
                sb.append("          }\n");
            }
            sb.append("          dead = true; break;\n");
            sb.append("        }\n");
        }
        sb.append("        default: dead = true; break;\n");
        sb.append("      }\n");
        sb.append("      if (dead) break;\n");
        sb.append("    }\n");
        // post-loop — A2: clone regs only once we know we need them
        sb.append("    if (haveAccept) {\n");
        sb.append("      if (anchored && lastAcceptPos != to) return null;\n");
        sb.append("      int[] r = regs.clone();\n");
        // emit per-accept-state finalRegops dispatch
        sb.append("      switch (lastAcceptState) {\n");
        for (int s = 0; s < nStates; s++) {
            int opsOff = stateFinalOpsOff[s];
            boolean isAccept = (stateMeta[s] & 1) != 0;
            if (!isAccept || opsOff == 0) continue;
            sb.append("        case ").append(s).append(": {\n");
            int j = opsOff;
            while (ops[j] != Tdfa.OP_END) {
                int op = ops[j], dst = ops[j + 1], src = ops[j + 2];
                sb.append("          ");
                switch (op) {
                    case Tdfa.OP_SET_POS: sb.append("r[").append(dst).append("] = lastAcceptPos;\n"); break;
                    case Tdfa.OP_SET_NIL: sb.append("r[").append(dst).append("] = -1;\n"); break;
                    case Tdfa.OP_COPY:    sb.append("r[").append(dst).append("] = r[").append(src).append("];\n"); break;
                }
                j += 3;
            }
            sb.append("          break;\n");
            sb.append("        }\n");
        }
        sb.append("      }\n");
        sb.append("      return new TdfaRunner.MatchHolder(matchStart, lastAcceptPos, r);\n");
        sb.append("    }\n");
        sb.append("    return null;\n");
        sb.append("  }\n");
        // isAccept(state)
        sb.append("  private static boolean isAccept(int state) {\n");
        sb.append("    switch (state) {\n");
        for (int s = 0; s < nStates; s++) {
            if ((stateMeta[s] & 1) != 0) sb.append("      case ").append(s).append(":\n");
        }
        sb.append("        return true;\n");
        sb.append("      default: return false;\n");
        sb.append("    }\n");
        sb.append("  }\n");
        // Per-state mask tables — class-level constants shared by entryOk/acceptOk.
        emitMaskTable(sb, "ENTRY_MASK", tdfa.stateEntryMask);
        emitMaskTable(sb, "ACCEPT_MASK", tdfa.stateAcceptMask);
        // Helpers reference ENTRY_MASK[state] / ACCEPT_MASK[state] above.
        // Mask helpers — compiled-in tables
        sb.append("  private static boolean entryOk(int state, int pos, int to, CharSequence input) {\n");
        sb.append("    int required = ENTRY_MASK[state];\n");
        sb.append("    if (required == 0) return true;\n");
        sb.append("    return (positionFlags(pos, to, input) & required) == required;\n");
        sb.append("  }\n");
        sb.append("  private static boolean acceptOk(int state, int pos, int to, CharSequence input) {\n");
        sb.append("    int required = ACCEPT_MASK[state];\n");
        sb.append("    if (required == 0) return true;\n");
        sb.append("    return (positionFlags(pos, to, input) & required) == required;\n");
        sb.append("  }\n");
        sb.append("  private static int positionFlags(int pos, int to, CharSequence input) {\n");
        sb.append("    int flags = 0;\n");
        sb.append("    if (pos == 0) flags |= ").append(Tnfa.BEGIN_TEXT).append(";\n");
        sb.append("    if (pos == to) flags |= ").append(Tnfa.END_TEXT).append(";\n");
        sb.append("    boolean prev = pos > 0 && isWord(input.charAt(pos - 1));\n");
        sb.append("    boolean curr = pos < to && isWord(input.charAt(pos));\n");
        sb.append("    if (prev != curr) flags |= ").append(Tnfa.WORD_BOUNDARY).append(";\n");
        sb.append("    else flags |= ").append(Tnfa.NO_WORD_BOUNDARY).append(";\n");
        sb.append("    return flags;\n");
        sb.append("  }\n");
        sb.append("  private static boolean isWord(char c) {\n");
        sb.append("    return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void emitMaskTable(StringBuilder sb, String name, int[] masks) {
        sb.append("  private static final int[] ").append(name).append(" = new int[]{");
        for (int i = 0; i < masks.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(masks[i]);
        }
        sb.append("};\n");
    }

    private static void emitOpsSource(StringBuilder sb, int[] ops, String arr, String posVar) {
        for (int i = 0; i < ops.length; i += 3) {
            int op = ops[i], dst = ops[i + 1], src = ops[i + 2];
            sb.append("            ");
            switch (op) {
                case Tdfa.OP_SET_POS: sb.append(arr).append("[").append(dst).append("] = ").append(posVar).append(";\n"); break;
                case Tdfa.OP_SET_NIL: sb.append(arr).append("[").append(dst).append("] = -1;\n"); break;
                case Tdfa.OP_COPY:    sb.append(arr).append("[").append(dst).append("] = ").append(arr).append("[").append(src).append("];\n"); break;
            }
        }
    }

    private static Class<?> compileAndLoad(String src, Tdfa tdfa) throws Exception {
        String className = "Gen_" + Math.abs(System.identityHashCode(tdfa));
        var compiler = ToolProvider.getSystemJavaCompiler();
        varFileManager fm = new varFileManager(compiler);
        var cu = List.<SimpleJavaFileObject>of(new SimpleJavaFileObject(
                URI.create("string:///gen/" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return src; }
        });
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new javax.tools.DiagnosticCollector<>();
        boolean ok = compiler.getTask(new java.io.PrintWriter(err), fm, diagnostics,
                List.of("--release", "17"), null, cu).call();
        if (!ok) throw new IllegalStateException("compile failed:\n" + src + "\n---\n" + err + "\n" + diagnostics.getDiagnostics());
        Class<?> cls = Class.forName("gen." + className, true, fm.cl);
        // Sanity: stash source for debugging via System.setProperty if needed.
        return cls;
    }

    /** In-memory file manager that captures emitted .class bytes and exposes a loader. */
    private static final class varFileManager extends javax.tools.ForwardingJavaFileManager<javax.tools.JavaFileManager> {
        final Map<String, byte[]> classes = new HashMap<>();
        final ClassLoader cl = new ClassLoader(TdfaAsmBackend.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b != null) { classes.put(name, null); return defineClass(name, b, 0, b.length); }
                return super.findClass(name);
            }
        };
        varFileManager(javax.tools.JavaCompiler compiler) {
            super(compiler.getStandardFileManager(null, null, null));
        }
        @Override public javax.tools.JavaFileObject getJavaFileForOutput(Location location,
                String className, javax.tools.JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("mem:///" + className + ".class"), javax.tools.JavaFileObject.Kind.CLASS) {
                @Override public OutputStream openOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override public void close() { classes.put(className, toByteArray()); }
                    };
                }
            };
        }
    }
}
