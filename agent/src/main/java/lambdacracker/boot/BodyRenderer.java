package lambdacracker.boot;

import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reconstructs a pseudo-source expression from a straight-line method body by simulating
 * the operand stack. Refuses to guess: any branch, store, try/catch, or unhandled shape
 * returns null and the caller falls back to a summary.
 */
final class BodyRenderer {
    // precedence levels for minimal parenthesisation
    private static final int ATOM = 100, UNARY = 90, MUL = 60, ADD = 50, SHIFT = 45, REL = 40,
            AND = 30, XOR = 25, OR = 20;

    private static final class Bail extends RuntimeException {
        Bail() { super(null, null, false, false); }
    }

    private record Expr(String text, int prec) {}

    private static final class NewMarker {
        final String type;
        NewMarker(String type) { this.type = type; }
    }

    private final CodeModel code;
    private final Map<Integer, String> slotNames;
    private final Deque<Object> stack = new ArrayDeque<>(); // Expr or NewMarker
    private final List<String> stmts = new ArrayList<>();

    private BodyRenderer(CodeModel code, Map<Integer, String> slotNames) {
        this.code = code;
        this.slotNames = slotNames;
    }

    static String render(CodeModel code, Map<Integer, String> slotNames) {
        try {
            return new BodyRenderer(code, slotNames).run();
        } catch (RuntimeException e) { // Bail or a genuine bug: either way, fall back
            return null;
        }
    }

    /**
     * Fallback for when {@link #render} bails: a compact disassembly, so the shape that
     * defeated expression reconstruction (a branch, a loop, a store) is still visible
     * instead of disappearing behind a generic summary.
     */
    static String textify(CodeModel code, Map<Integer, String> slotNames) {
        try {
            Map<Label, String> labels = new IdentityHashMap<>();
            List<String> parts = new ArrayList<>();
            for (CodeElement e : code.elementList()) {
                switch (e) {
                    case LabelTarget lt -> parts.add(labelName(labels, lt.label()) + ":");
                    case LineNumber ln -> {}
                    case LocalVariable lv -> {}
                    case LocalVariableType lvt -> {}
                    case ExceptionCatch ec -> parts.add("catch " +
                            ec.catchType().map(ct -> simple(ct.asInternalName())).orElse("any"));
                    case BranchInstruction b -> parts.add(mnemonic(b.opcode()) + " " + labelName(labels, b.target()));
                    case LookupSwitchInstruction ls -> parts.add(mnemonic(ls.opcode()) + " (" + ls.cases().size() + " cases)");
                    case TableSwitchInstruction ts -> parts.add(mnemonic(ts.opcode()) + " (" + (ts.highValue() - ts.lowValue() + 1) + " cases)");
                    case IncrementInstruction inc -> parts.add("iinc " + slotName(slotNames, inc.slot()) + " "
                            + (inc.constant() >= 0 ? "+" : "") + inc.constant());
                    case LoadInstruction l -> parts.add(mnemonic(l.opcode()) + " " + slotName(slotNames, l.slot()));
                    case StoreInstruction s -> parts.add(mnemonic(s.opcode()) + " " + slotName(slotNames, s.slot()));
                    case FieldInstruction f -> parts.add(mnemonic(f.opcode()) + " "
                            + simple(f.owner().asInternalName()) + "." + f.name().stringValue());
                    case InvokeInstruction inv -> parts.add(mnemonic(inv.opcode()) + " "
                            + simple(inv.owner().asInternalName()) + "." + inv.name().stringValue());
                    case InvokeDynamicInstruction id -> parts.add("invokedynamic " + id.name().stringValue());
                    case ConstantInstruction c -> parts.add("ldc " + literal(c.constantValue()));
                    case NewObjectInstruction n -> parts.add("new " + simple(n.className().asInternalName()));
                    case TypeCheckInstruction tc -> parts.add(mnemonic(tc.opcode()) + " " + simple(tc.type().asInternalName()));
                    case Instruction ins -> parts.add(mnemonic(ins.opcode()));
                    default -> {} // other pseudo-instructions (character ranges, ...): skip
                }
            }
            return String.join("; ", parts);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String labelName(Map<Label, String> labels, Label l) {
        return labels.computeIfAbsent(l, k -> "L" + labels.size());
    }

    private static String slotName(Map<Integer, String> slotNames, int slot) {
        String n = slotNames.get(slot);
        return n != null ? n : "v" + slot;
    }

    private static String mnemonic(Opcode op) {
        return op.name().toLowerCase(Locale.ROOT);
    }

    private static String literal(ConstantDesc v) {
        return switch (v) {
            case null -> "null";
            case String s -> quote(s);
            case ClassDesc cd -> simple(cd.displayName()) + ".class";
            default -> String.valueOf(v);
        };
    }

    private String run() {
        for (CodeElement e : code.elementList()) {
            switch (e) {
                case LineNumber ln -> {}
                case LocalVariable lv -> {}
                case LocalVariableType lvt -> {}
                case LabelTarget l -> {} // LVT ranges introduce labels even without branches
                case NopInstruction n -> {}
                case ExceptionCatch ec -> throw new Bail();
                case ConstantInstruction c -> push(constant(c.constantValue()));
                case LoadInstruction l -> push(new Expr(nameOf(l.slot()), ATOM));
                case FieldInstruction f -> field(f);
                case InvokeInstruction inv -> invoke(inv);
                case InvokeDynamicInstruction indy -> indy(indy);
                case OperatorInstruction op -> operator(op);
                case NewObjectInstruction n -> stack.push(new NewMarker(simple(n.className().asInternalName())));
                case StackInstruction s -> stackOp(s);
                case TypeCheckInstruction tc -> typeCheck(tc);
                case ConvertInstruction cv -> {} // numeric widening/narrowing: render transparently
                case ReturnInstruction r -> { return finish(r); }
                default -> throw new Bail(); // stores, branches, switches, throws, arrays, iinc, ...
            }
        }
        throw new Bail();
    }

    private String finish(ReturnInstruction r) {
        if (r.opcode() == Opcode.RETURN) {
            if (!stack.isEmpty()) throw new Bail();
            return stmts.isEmpty() ? "()" : String.join("; ", stmts);
        }
        Expr v = popExpr();
        if (!stack.isEmpty()) throw new Bail();
        return stmts.isEmpty() ? v.text : String.join("; ", stmts) + "; " + v.text;
    }

    private void field(FieldInstruction f) {
        switch (f.opcode()) {
            case GETFIELD -> push(new Expr(qual(popExpr(), ATOM) + "." + f.name().stringValue(), ATOM));
            case GETSTATIC -> {
                String owner = f.owner().asInternalName();
                String name = f.name().stringValue();
                if (owner.equals("scala/runtime/BoxedUnit") && name.equals("UNIT")) push(new Expr("()", ATOM));
                else if (name.equals("MODULE$")) push(new Expr(moduleName(owner), ATOM)); // Scala object
                else push(new Expr(simple(owner) + "." + name, ATOM));
            }
            default -> throw new Bail(); // putfield/putstatic
        }
    }

    private void invoke(InvokeInstruction inv) {
        String owner = inv.owner().asInternalName();
        String name = inv.name().stringValue();
        MethodTypeDesc mtd = inv.typeSymbol();
        int argc = mtd.parameterCount();
        boolean isStatic = inv.opcode() == Opcode.INVOKESTATIC;

        // boxing is invisible in source; render it that way
        if (isStatic && argc == 1
                && (owner.equals("scala/runtime/BoxesRunTime") && (name.startsWith("boxTo") || name.startsWith("unboxTo"))
                    || isBox(owner) && name.equals("valueOf"))) return;
        if (!isStatic && argc == 0 && isBox(owner) && name.endsWith("Value")) return;

        List<Expr> args = popArgs(argc);
        if (name.equals("<init>")) { ctor(args); return; }

        String text;
        if (isStatic) {
            text = simple(owner) + "." + name + argList(args);
        } else {
            String recv = qual(popExpr(), ATOM);
            if (name.equals("apply") || name.startsWith("apply$mc")) text = recv + argList(args); // f(x)
            else if (args.isEmpty()) text = prefixRecv(recv) + name;                              // p.amount
            else text = prefixRecv(recv) + name + argList(args);
        }
        if (mtd.returnType().descriptorString().equals("V")) stmts.add(text);
        else push(new Expr(text, ATOM));
    }

    /** new T + dup + <init> collapses to "new T(args)". */
    private void ctor(List<Expr> args) {
        if (!(stack.poll() instanceof NewMarker m)) throw new Bail();
        Expr e = new Expr("new " + m.type + argList(args), ATOM);
        if (stack.peek() == m) {
            stack.pop();
            stack.push(e);
        } else {
            stmts.add(e.text); // un-dup'd new: expression statement
        }
    }

    private void indy(InvokeDynamicInstruction indy) {
        String bsmOwner = indy.bootstrapMethod().owner().descriptorString();
        int argc = indy.typeSymbol().parameterCount();
        if (bsmOwner.equals("Ljava/lang/invoke/StringConcatFactory;")) {
            List<Expr> args = popArgs(argc);
            push(concat(indy, args));
        } else if (bsmOwner.equals("Ljava/lang/invoke/LambdaMetafactory;")) {
            popArgs(argc); // captures
            push(new Expr("«λ»", ATOM)); // nested lambda: don't recurse in phase 1
        } else {
            throw new Bail();
        }
    }

    private Expr concat(InvokeDynamicInstruction indy, List<Expr> args) {
        String recipe;
        if (indy.name().equalsString("makeConcat")) {
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < args.size(); i++) r.append('\1');
            recipe = r.toString();
        } else {
            var bootstrapArgs = indy.bootstrapArgs();
            if (bootstrapArgs.isEmpty() || !(bootstrapArgs.get(0) instanceof String s)) throw new Bail();
            recipe = s;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int argIdx = 0;
        for (int i = 0; i < recipe.length(); i++) {
            char c = recipe.charAt(i);
            if (c == '\1') {
                if (lit.length() > 0) { parts.add(quote(lit.toString())); lit.setLength(0); }
                if (argIdx >= args.size()) throw new Bail();
                parts.add(qual(args.get(argIdx++), ADD + 1));
            } else if (c == '\2') {
                throw new Bail(); // constant placeholders: rare, punt
            } else {
                lit.append(c);
            }
        }
        if (lit.length() > 0) parts.add(quote(lit.toString()));
        if (parts.isEmpty()) parts.add("\"\"");
        return new Expr(String.join(" + ", parts), ADD);
    }

    private void operator(OperatorInstruction op) {
        switch (op.opcode()) {
            case IADD, LADD, FADD, DADD -> bin("+", ADD);
            case ISUB, LSUB, FSUB, DSUB -> bin("-", ADD);
            case IMUL, LMUL, FMUL, DMUL -> bin("*", MUL);
            case IDIV, LDIV, FDIV, DDIV -> bin("/", MUL);
            case IREM, LREM, FREM, DREM -> bin("%", MUL);
            case ISHL, LSHL -> bin("<<", SHIFT);
            case ISHR, LSHR -> bin(">>", SHIFT);
            case IUSHR, LUSHR -> bin(">>>", SHIFT);
            case IAND, LAND -> bin("&", AND);
            case IXOR, LXOR -> bin("^", XOR);
            case IOR, LOR -> bin("|", OR);
            case INEG, LNEG, FNEG, DNEG -> push(new Expr("-" + qual(popExpr(), UNARY), UNARY));
            case ARRAYLENGTH -> push(new Expr(qual(popExpr(), ATOM) + ".length", ATOM));
            default -> throw new Bail(); // comparisons come with branches anyway
        }
    }

    private void stackOp(StackInstruction s) {
        switch (s.opcode()) {
            case DUP -> {
                if (stack.peek() instanceof NewMarker m) stack.push(m);
                else throw new Bail();
            }
            case POP -> stmts.add(text(stack.pop()));
            default -> throw new Bail();
        }
    }

    private void typeCheck(TypeCheckInstruction tc) {
        if (tc.opcode() == Opcode.CHECKCAST) return; // transparent
        Expr e = popExpr(); // INSTANCEOF
        push(new Expr(qual(e, REL) + " instanceof " + simple(tc.type().asInternalName()), REL));
    }

    private Expr constant(ConstantDesc v) {
        return switch (v) {
            case null -> new Expr("null", ATOM);
            case Integer i -> new Expr(i.toString(), i < 0 ? UNARY : ATOM);
            case Long l -> new Expr(l + "L", l < 0 ? UNARY : ATOM);
            case Float f -> new Expr(f + "f", f < 0 ? UNARY : ATOM);
            case Double d -> new Expr(d.toString(), d < 0 ? UNARY : ATOM);
            case String s -> new Expr(quote(s), ATOM);
            case ClassDesc cd -> new Expr(simple(cd.displayName()) + ".class", ATOM);
            default -> throw new Bail();
        };
    }

    private void bin(String sym, int prec) {
        Expr right = popExpr();
        Expr left = popExpr();
        push(new Expr(qual(left, prec) + " " + sym + " " + qual(right, prec + 1), prec));
    }

    // --- helpers ---

    private void push(Expr e) { stack.push(e); }

    private Expr popExpr() {
        if (stack.poll() instanceof Expr e) return e;
        throw new Bail();
    }

    private List<Expr> popArgs(int n) {
        Expr[] args = new Expr[n];
        for (int i = n - 1; i >= 0; i--) args[i] = popExpr();
        return List.of(args);
    }

    private String argList(List<Expr> args) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).text);
        }
        return sb.append(')').toString();
    }

    private static String qual(Expr e, int minPrec) {
        return e.prec < minPrec ? "(" + e.text + ")" : e.text;
    }

    private static String text(Object stackVal) {
        if (stackVal instanceof Expr e) return e.text;
        throw new Bail();
    }

    private String nameOf(int slot) {
        String n = slotNames.get(slot);
        return n != null ? n : "v" + slot;
    }

    private static String prefixRecv(String recv) {
        return recv.isEmpty() ? "" : recv + ".";
    }

    private static boolean isBox(String owner) {
        return switch (owner) {
            case "java/lang/Integer", "java/lang/Long", "java/lang/Double", "java/lang/Float",
                 "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Boolean" -> true;
            default -> false;
        };
    }

    private static String moduleName(String internalName) {
        String s = simple(internalName);
        return s.equals("Predef") ? "" : s; // Predef members read as bare names in Scala
    }

    private static String simple(String internalName) {
        String s = internalName.substring(internalName.lastIndexOf('/') + 1);
        if (s.endsWith("$")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String quote(String s) {
        if (s.length() > 40) s = s.substring(0, 39) + "…";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
