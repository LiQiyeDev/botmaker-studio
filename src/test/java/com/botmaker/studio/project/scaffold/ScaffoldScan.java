package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.project.scaffold.ScaffoldSurface.Element;
import com.botmaker.studio.project.scaffold.ScaffoldSurface.Origin;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.JavaCore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the SDK elements a body of generated source actually names — the other half of
 * {@link ScaffoldSurface}, and the only thing that can tell a declaration from a wish.
 *
 * <h2>Lexical, like every other scanner in this repo</h2>
 *
 * <p>Judged from source alone, with no bindings, exactly as {@code parser/refactor/SdkReferences} is: the
 * corpus is a handful of text blocks with no classpath behind them and nothing to resolve against. A name is
 * attributed to the SDK when the source itself writes it — spelled in full, or spelled short with an
 * {@code import com.botmaker.sdk.…} above it. The known-type set is {@link SdkType}, which is compiler-checked
 * against the jar Studio builds on, so "is this an SDK type" is not a guess either.
 *
 * <p>It is <b>not</b> {@code SdkReferences} itself. That class answers a question about a user's project — it
 * needs a {@code ProjectFile}, it yields simple names and {@code CallSite}s rather than FQNs and arities, and
 * it does not read the three shapes this corpus is mostly made of: a method reference
 * ({@code GoHome.INSTANCE::execute}), an {@code @Override} declaration ({@code isEnabled}, {@code run}), and a
 * call through an instance ({@code ActivityRegistry.MINING.execute()}). Sharing one walk between two questions
 * that different would cost more than the ninety lines below.
 *
 * <h2>The type-position rule</h2>
 *
 * <p>A <b>type</b> is recorded only where the source writes it in a type position — {@code extends}, a field
 * or parameter or return type, a type argument, a {@code .class} literal, a constant read's qualifier. A name
 * that merely qualifies a static call is <em>not</em> a type sighting: {@code Bot.start(…)} names the member.
 * That is the same line the SDK's {@code @Scaffolding} annotations are drawn on, and holding the two to it is
 * what lets the two repositories compare one file.
 *
 * <p>An {@code import} is not a sighting either — it is how a short name is spelled, not something the file
 * uses. Neither is anything inside a Javadoc comment; the seed files carry worked examples in their doc
 * comments ({@code ImageClicker.click}, {@code Wait.seconds}) that the generated bot never calls.
 *
 * <h2>Arity, and the one place it is not written down</h2>
 *
 * <p>Every shape above carries its argument count except a method reference, which is a name and nothing else.
 * Those are collected as {@link #ARITY_UNKNOWN} and then resolved: folded into a known-arity sighting of the
 * same member when the corpus also calls it outright, and otherwise looked up on the SDK class itself, which
 * answers only when the member has exactly one overload. An unresolvable one is an error rather than a guess —
 * a wrong arity in the surface file would fail the SDK's gate with a difference nobody could act on.
 */
public final class ScaffoldScan {

    private ScaffoldScan() {}

    /** A method reference names no arguments; see the class javadoc for how these are resolved. */
    public static final int ARITY_UNKNOWN = -2;

    /** One generated file, and which generator wrote it. */
    public record Source(String fileName, String text, Origin origin) {}

    /** The identity a sighting is deduped on: two files naming {@code Bot.stop()} are one element, two origins. */
    private record Key(String type, String member, int arity) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byType = type.compareTo(other.type);
            if (byType != 0) return byType;
            int byMember = member.compareTo(other.member);
            return byMember != 0 ? byMember : Integer.compare(arity, other.arity);
        }
    }

    /**
     * Every SDK element {@code sources} names, in {@link Element} form and sorted, ready to be compared with
     * {@link ScaffoldSurface#all()}.
     *
     * @throws IllegalStateException if a file does not parse, or a method reference's arity cannot be resolved
     */
    public static List<Element> collect(List<Source> sources) {
        Set<String> sdkTypes = new HashSet<>();
        for (SdkType type : SdkType.values()) sdkTypes.add(type.qualifiedName());

        // Parse everything first: an instance receiver is resolved through the corpus (GoHome.INSTANCE is a
        // GoHome, which extends Activity), so no file can be scanned until every file has been read.
        Map<Source, CompilationUnit> units = new LinkedHashMap<>();
        for (Source source : sources) units.put(source, parse(source));

        Corpus corpus = new Corpus(sdkTypes);
        for (CompilationUnit unit : units.values()) indexCorpus(unit, corpus, imports(unit));

        Map<Key, EnumSet<Origin>> found = new TreeMap<>();
        for (Map.Entry<Source, CompilationUnit> entry : units.entrySet()) {
            Sightings sink = (type, member, arity) -> found
                    .computeIfAbsent(new Key(type, member, arity), k -> EnumSet.noneOf(Origin.class))
                    .add(entry.getKey().origin());
            entry.getValue().accept(new Collector(sdkTypes, imports(entry.getValue()), corpus, sink));
        }

        List<Element> out = new ArrayList<>();
        for (Map.Entry<Key, EnumSet<Origin>> entry : declaredArities(found).entrySet()) {
            Key key = entry.getKey();
            out.add(new Element(key.type(), key.member(), key.arity(), entry.getValue()));
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ------------------------------------------------------------------
    // arity resolution
    // ------------------------------------------------------------------

    /**
     * Turns each sighting's <em>argument</em> count into the SDK member's <em>declared parameter</em> count.
     *
     * <p>The two are not the same number, and the difference is not a detail: {@code ImageTemplateGroup.of()}
     * passes nothing to a varargs parameter, so the call says 0 and the declaration says 1. The surface has to
     * record the declaration, because the SDK's gate builds its half from
     * {@code MethodInfo.getParameterInfo().length} and has no call site to count. Resolving here is also what
     * lets a {@link #ARITY_UNKNOWN} method reference land on a real number — folded into a sighting the corpus
     * already resolved, or looked up when the SDK declares exactly one member of that name.
     */
    private static Map<Key, EnumSet<Origin>> declaredArities(Map<Key, EnumSet<Origin>> found) {
        Map<Key, EnumSet<Origin>> out = new TreeMap<>();
        List<Map.Entry<Key, EnumSet<Origin>>> unknown = new ArrayList<>();
        for (Map.Entry<Key, EnumSet<Origin>> entry : found.entrySet()) {
            Key key = entry.getKey();
            if (key.arity() == ARITY_UNKNOWN) {
                unknown.add(entry);
            } else if (key.arity() == ScaffoldSurface.NO_ARITY) {
                merge(out, key, entry.getValue());
            } else {
                merge(out, new Key(key.type(), key.member(), declaredArityOf(key)), entry.getValue());
            }
        }
        for (Map.Entry<Key, EnumSet<Origin>> entry : unknown) {
            Key key = entry.getKey();
            Key sibling = out.keySet().stream()
                    .filter(k -> k.type().equals(key.type()) && k.member().equals(key.member()))
                    .findFirst().orElseGet(() -> new Key(key.type(), key.member(), soleArityOf(key)));
            merge(out, sibling, entry.getValue());
        }
        return out;
    }

    private static void merge(Map<Key, EnumSet<Origin>> into, Key key, EnumSet<Origin> origins) {
        into.computeIfAbsent(key, k -> EnumSet.noneOf(Origin.class)).addAll(origins);
    }

    /** The parameter count of the member a call of {@code key.arity()} arguments actually reaches. */
    private static int declaredArityOf(Key key) {
        List<Integer> counts = new ArrayList<>();
        List<Integer> varargs = new ArrayList<>();
        for (Object member : membersNamed(key)) {
            int count = member instanceof Method m ? m.getParameterCount()
                    : ((Constructor<?>) member).getParameterCount();
            boolean isVarargs = member instanceof Method m ? m.isVarArgs()
                    : ((Constructor<?>) member).isVarArgs();
            if (count == key.arity()) counts.add(count);
            else if (isVarargs && count - 1 <= key.arity()) varargs.add(count);
        }
        if (!counts.isEmpty()) return counts.getFirst();
        if (varargs.size() == 1) return varargs.getFirst();
        throw new IllegalStateException("The scaffold calls " + key.type() + "#" + key.member() + " with "
                + key.arity() + " argument(s), and the SDK on Studio's classpath declares no member of that "
                + "name it could reach" + (varargs.size() > 1 ? " unambiguously" : "")
                + ". Fix the generator, or the SDK moved the member and its @ReplacedBy pointer says where.");
    }

    /** The parameter count of {@code key}'s member, when the SDK declares exactly one of that name. */
    private static int soleArityOf(Key key) {
        List<Object> named = membersNamed(key);
        if (named.size() != 1) {
            throw new IllegalStateException("The scaffold names " + key.type() + "#" + key.member()
                    + " only as a method reference, which carries no arity, and the SDK declares "
                    + named.size() + " members of that name — so the arity cannot be resolved. Either call it "
                    + "outright somewhere in the generated source, or resolve it here by hand.");
        }
        Object only = named.getFirst();
        return only instanceof Method m ? m.getParameterCount() : ((Constructor<?>) only).getParameterCount();
    }

    /** Every public method or constructor of {@code key.type()} spelled {@code key.member()}. */
    private static List<Object> membersNamed(Key key) {
        Class<?> declaring;
        try {
            declaring = Class.forName(key.type());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The scaffold names " + key.type() + ", which the SDK on Studio's "
                    + "classpath does not have.", e);
        }
        List<Object> found = new ArrayList<>();
        if (ScaffoldSurface.CTOR.equals(key.member())) {
            found.addAll(List.of(declaring.getConstructors()));
        } else {
            for (Method m : declaring.getMethods()) {
                if (m.getName().equals(key.member()) && !m.isSynthetic()) found.add(m);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    private static CompilationUnit parse(Source source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.text().toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        if (unit.types().isEmpty()) {
            throw new IllegalStateException(source.fileName() + " did not parse into a type declaration — the "
                    + "generator emitted source that is not Java:\n" + source.text());
        }
        return unit;
    }

    /** Simple name → SDK FQN, for the single-type imports this file carries. */
    private static Map<String, String> imports(CompilationUnit unit) {
        Map<String, String> out = new HashMap<>();
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isOnDemand() || imported.isStatic()) continue;
            String fqn = imported.getName().getFullyQualifiedName();
            if (!fqn.startsWith("com.botmaker.sdk.")) continue;
            out.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // the corpus index — what the generated classes themselves are
    // ------------------------------------------------------------------

    /**
     * The one level of indirection an instance receiver needs. {@code ActivityRegistry.MINING.execute()} says
     * nothing about the SDK on its face; the corpus does: {@code MINING} is declared a {@code Mining}, and
     * {@code Mining extends Activity}. Both facts come from generated files, which is why they can be read.
     */
    private static final class Corpus {
        private final Set<String> sdkTypes;
        /** Corpus class simple name → the SDK type it extends. */
        private final Map<String, String> superOf = new HashMap<>();
        /** {@code Owner.FIELD} → the simple name of the field's declared type. */
        private final Map<String, String> fieldTypes = new HashMap<>();

        Corpus(Set<String> sdkTypes) {
            this.sdkTypes = sdkTypes;
        }

        boolean isSdk(String fqn) {
            return sdkTypes.contains(fqn);
        }

        void recordSuperclass(String corpusClass, String sdkSuperclass) {
            superOf.put(corpusClass, sdkSuperclass);
        }

        void recordField(String owner, String field, String declaredType) {
            fieldTypes.put(owner + "." + field, declaredType);
        }

        /** The SDK type a {@code Owner.FIELD} receiver is an instance of, or null when it is not one. */
        String sdkTypeOfField(String owner, String field) {
            String declared = fieldTypes.get(owner + "." + field);
            return declared == null ? null : superOf.get(declared);
        }
    }

    /** Fills {@link Corpus} — the superclass of every generated type, and the type of every field it declares. */
    private static void indexCorpus(CompilationUnit unit, Corpus corpus, Map<String, String> imports) {
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                Type superclass = node.getSuperclassType();
                String sdk = superclass == null ? null : sdkNameOfType(superclass, corpus, imports);
                if (sdk != null) corpus.recordSuperclass(node.getName().getIdentifier(), sdk);
                for (FieldDeclaration field : node.getFields()) {
                    String declared = simpleNameOfType(field.getType());
                    if (declared == null) continue;
                    for (Object fragment : field.fragments()) {
                        corpus.recordField(node.getName().getIdentifier(),
                                ((VariableDeclarationFragment) fragment).getName().getIdentifier(), declared);
                    }
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------
    // the collector
    // ------------------------------------------------------------------

    /** Where a sighting goes; {@code arity} is {@link ScaffoldSurface#NO_ARITY} for a type. */
    @FunctionalInterface
    private interface Sightings {
        void saw(String type, String member, int arity);
    }

    private static final class Collector extends ASTVisitor {

        private final Set<String> sdkTypes;
        private final Map<String, String> imports;
        private final Corpus corpus;
        private final Sightings sink;

        Collector(Set<String> sdkTypes, Map<String, String> imports, Corpus corpus, Sightings sink) {
            this.sdkTypes = sdkTypes;
            this.imports = imports;
            this.corpus = corpus;
            this.sink = sink;
        }

        // An import is how a short name is spelled; a package declaration and a doc comment are not code.
        @Override
        public boolean visit(ImportDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(PackageDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(Javadoc node) {
            return false;
        }

        /** Every type position: {@code extends}, a field/parameter/return type, a type argument, {@code .class}. */
        @Override
        public boolean visit(SimpleType node) {
            String fqn = resolve(node.getName());
            if (fqn != null) sink.saw(fqn, ScaffoldSurface.TYPE_ONLY, ScaffoldSurface.NO_ARITY);
            return true;
        }

        /** A constant read — {@code Key.A}. The constant itself is not an element; its type is. */
        @Override
        public boolean visit(QualifiedName node) {
            // The qualifier of a static call is not a type sighting: `Bot.start(…)` names the member. A
            // qualified name in a type position reaches this visitor through visit(SimpleType) instead.
            ASTNode parent = node.getParent();
            if (parent instanceof MethodInvocation call && call.getExpression() == node) return true;
            String fqn = longestSdkPrefix(node.getFullyQualifiedName());
            if (fqn != null) sink.saw(fqn, ScaffoldSurface.TYPE_ONLY, ScaffoldSurface.NO_ARITY);
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            String fqn = sdkNameOfType(node.getType(), corpus, imports);
            if (fqn != null) sink.saw(fqn, ScaffoldSurface.CTOR, node.arguments().size());
            return true;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            String receiver = receiverType(node.getExpression());
            if (receiver != null) {
                sink.saw(receiver, node.getName().getIdentifier(), node.arguments().size());
            }
            return true;
        }

        @Override
        public boolean visit(ExpressionMethodReference node) {
            String receiver = receiverType(node.getExpression());
            if (receiver != null) sink.saw(receiver, node.getName().getIdentifier(), ARITY_UNKNOWN);
            return true;
        }

        /**
         * An {@code @Override} in a class extending an SDK type: the member belongs to the supertype, and
         * nothing else in the file says its name. This is how {@code Activity#isEnabled} and
         * {@code Activity#run} are seen at all.
         */
        @Override
        public boolean visit(MethodDeclaration node) {
            if (!isOverride(node)) return true;
            if (!(node.getParent() instanceof TypeDeclaration owner)) return true;
            Type superclass = owner.getSuperclassType();
            String fqn = superclass == null ? null : sdkNameOfType(superclass, corpus, imports);
            if (fqn != null) sink.saw(fqn, node.getName().getIdentifier(), node.parameters().size());
            return true;
        }

        private static boolean isOverride(MethodDeclaration node) {
            for (Object modifier : node.modifiers()) {
                if (modifier instanceof org.eclipse.jdt.core.dom.Annotation a
                        && "Override".equals(a.getTypeName().getFullyQualifiedName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The SDK type a call's receiver stands for: the type itself for a static call, or — through the
         * corpus — the SDK supertype of the singleton a generated file happens to route through.
         */
        private String receiverType(Object expression) {
            if (!(expression instanceof Name name)) return null;
            String direct = resolve(name);
            if (direct != null) return direct;
            if (name instanceof QualifiedName qualified) {
                return corpus.sdkTypeOfField(qualified.getQualifier().getFullyQualifiedName(),
                        qualified.getName().getIdentifier());
            }
            return null;
        }

        /** A written name as an SDK FQN: spelled in full, or spelled short with an import above it. */
        private String resolve(Name name) {
            String written = name.getFullyQualifiedName();
            if (sdkTypes.contains(written)) return written;
            return name.isSimpleName() ? imports.get(written) : longestSdkPrefix(written);
        }

        /** {@code …interaction.Key.A} is the SDK type {@code Key} plus a constant name we do not record. */
        private String longestSdkPrefix(String dotted) {
            for (int dot = dotted.lastIndexOf('.'); dot > 0; dot = dotted.lastIndexOf('.', dot - 1)) {
                String prefix = dotted.substring(0, dot);
                if (sdkTypes.contains(prefix)) return prefix;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // shared type helpers
    // ------------------------------------------------------------------

    /** The SDK FQN a type node names, unwrapping a parameterized type to its base. */
    private static String sdkNameOfType(Type type, Corpus corpus, Map<String, String> imports) {
        Type base = type instanceof ParameterizedType parameterized ? parameterized.getType() : type;
        if (!(base instanceof SimpleType simple)) return null;
        String written = simple.getName().getFullyQualifiedName();
        if (corpus.isSdk(written)) return written;
        String imported = imports.get(written);
        return imported != null && corpus.isSdk(imported) ? imported : null;
    }

    /** The simple name of a declared type, for the corpus field index — {@code GoHome}, {@code Mining}. */
    private static String simpleNameOfType(Type type) {
        Type base = type instanceof ParameterizedType parameterized ? parameterized.getType() : type;
        if (!(base instanceof SimpleType simple)) return null;
        String written = simple.getName().getFullyQualifiedName();
        return written.substring(written.lastIndexOf('.') + 1);
    }
}
