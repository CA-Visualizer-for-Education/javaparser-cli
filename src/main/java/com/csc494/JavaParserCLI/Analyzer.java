package com.csc494.JavaParserCLI;

import com.csc494.JavaParserCLI.dto.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.github.javaparser.utils.SourceRoot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Parses a source tree into an ASG: type/method/field nodes plus the edges
 * between them.
 */
public class Analyzer {

  private final List<ASGNode> nodes = new ArrayList<>();
  private final List<ASGEdge> edges = new ArrayList<>();

  public CodebaseReport analyze(Path sourceDir) throws Exception {
    nodes.clear();
    edges.clear();

    // Symbol solver lets us resolve calls and types to fully qualified names
    CombinedTypeSolver typeSolver = new CombinedTypeSolver();
    typeSolver.add(new ReflectionTypeSolver());
    typeSolver.add(new JavaParserTypeSolver(sourceDir));

    ParserConfiguration config = new ParserConfiguration().setSymbolResolver(
        new JavaSymbolSolver(typeSolver));
    SourceRoot sourceRoot = new SourceRoot(sourceDir, config);

    List<CompilationUnit> cus =
        sourceRoot.tryToParse()
            .stream()
            .filter(res -> res.isSuccessful() && res.getResult().isPresent())
            .map(res -> res.getResult().get())
            .toList();

    for (CompilationUnit cu : cus) {
      cu.accept(new VoidVisitorAdapter<Void>() {
        @Override
        // first, itereate over all the types in the compilation unit (classes,
        // interfaces, enums) and creates nodes for them
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
          super.visit(n, arg);

          String fqn = typeFqn(n);
          String pkg = packageOf(cu);

          if (n.isInterface()) {
            // InterfaceNode + EXTENDS edges (interface -> super-interfaces)
            nodes.add(
                new InterfaceNode(fqn, n.getNameAsString(), pkg,
                                  modifiers(n, InterfaceModifier::valueOf)));

            n.getExtendedTypes().forEach(
                t -> edges.add(new ExtendsEdge(typeName(t), fqn)));
          } else {
            // ClassNode + EXTENDS / IMPLEMENTS edges
            nodes.add(new ClassNode(fqn, n.getNameAsString(), pkg,
                                    modifiers(n, ClassModifier::valueOf)));

            n.getExtendedTypes().forEach(
                t -> edges.add(new ExtendsEdge(typeName(t), fqn)));
            n.getImplementedTypes().forEach(
                t -> edges.add(new ImplementsEdge(typeName(t), fqn)));
          }

          processMembers(n, fqn);
        }

        @Override
        public void visit(EnumDeclaration n, Void arg) {
          super.visit(n, arg);

          String fqn = typeFqn(n);
          List<String> constants = n.getEntries()
                                       .stream()
                                       .map(e -> e.getNameAsString())
                                       .collect(Collectors.toList());

          // EnumNode + IMPLEMENTS edges
          nodes.add(
              new EnumNode(fqn, n.getNameAsString(), packageOf(cu), constants));

          n.getImplementedTypes().forEach(
              t -> edges.add(new ImplementsEdge(typeName(t), fqn)));

          processMembers(n, fqn);
        }
      }, null);
    }

    return new CodebaseReport(new ArrayList<>(nodes), new ArrayList<>(edges));
  }

  /**
   * Emits field and method nodes for a type, plus their HAS_FIELD / HAS_METHOD
   * edges.
   */
  private void processMembers(TypeDeclaration<?> type, String ownerFqn) {
    // FieldNode + HAS_FIELD (one per declared variable)
    type.getFields().forEach(field -> {
      List<FieldModifier> mods = modifiers(field, FieldModifier::valueOf);

      field.getVariables().forEach(v -> {
        String fieldFqn = ownerFqn + "." + v.getNameAsString();

        nodes.add(new FieldNode(fieldFqn, v.getNameAsString(),
                                resolveTypeName(v.getType()), mods));
        edges.add(new HasFieldEdge(ownerFqn, fieldFqn));
      });
    });

    // MethodNode + HAS_METHOD, then CALLS / MUTATES_FIELD from its body
    for (MethodDeclaration method : type.getMethods()) {
      String methodFqn = methodFqn(method, ownerFqn);
      String params = method.getParameters()
                          .stream()
                          .map(p -> resolveTypeName(p.getType()))
                          .collect(Collectors.joining(","));

      nodes.add(new MethodNode(methodFqn, method.getNameAsString(),
                               resolveTypeName(method.getType()), params,
                               modifiers(method, MethodModifier::valueOf)));
      edges.add(new HasMethodEdge(ownerFqn, methodFqn));

      processMethodBody(method, methodFqn);
    }
  }

  /** Emits CALLS and MUTATES_FIELD edges for everything inside one method. */
  private void processMethodBody(MethodDeclaration method, String methodFqn) {
    method.accept(new VoidVisitorAdapter<Void>() {
      // CALLS edge for a regular method call
      @Override
      public void visit(MethodCallExpr call, Void arg) {
        super.visit(call, arg);

        try {
          String called = call.resolve().getQualifiedSignature();
          String target =
              call.getScope().map(Analyzer.this::scopeType).orElse(null);

          edges.add(new CallsEdge(methodFqn, called, target, insideLoop(call),
                                  insideConditional(call), false));
        } catch (Exception | StackOverflowError e) {
          // unresolvable call (e.g. external library)
        }
      }

      // CALLS edge for a constructor call (`new Foo(...)`)
      @Override
      public void visit(ObjectCreationExpr call, Void arg) {
        super.visit(call, arg);

        try {
          String called = call.resolve().getQualifiedSignature();

          edges.add(new CallsEdge(methodFqn, called, null, insideLoop(call),
                                  insideConditional(call), true));
        } catch (Exception | StackOverflowError e) {
          // unresolvable constructor
        }
      }

      // MUTATES_FIELD edge when an assignment targets a field
      @Override
      public void visit(AssignExpr assign, Void arg) {
        super.visit(assign, arg);

        Expression target = assign.getTarget();

        if (!(target instanceof NameExpr) &&
            !(target instanceof FieldAccessExpr)) {
          return;
        }

        try {
          ResolvedValueDeclaration decl =
              target instanceof NameExpr ? ((NameExpr)target).resolve()
                                         : ((FieldAccessExpr)target).resolve();

          // locals and parameters are not fields
          if (decl.isField()) {
            var field = decl.asField();
            String fieldFqn = field.declaringType().getQualifiedName() + "." +
                              field.getName();

            edges.add(new MutatesFieldEdge(methodFqn, fieldFqn,
                                           MutatingOperation.ASSIGN));
          }
        } catch (Exception | StackOverflowError e) {
          // unresolvable target
        }
      }
    }, null);
  }

  /** Type of the object a method was called on, or null if unresolvable. */
  private String scopeType(Expression scope) {
    try {
      return scope.calculateResolvedType().describe();
    } catch (Exception | StackOverflowError e) {
      return null;
    }
  }

  private static boolean insideLoop(Node n) {
    return hasAncestorBeforeMethod(
        n,
        p
        -> p instanceof ForStmt || p instanceof ForEachStmt ||
               p instanceof WhileStmt || p instanceof DoStmt);
  }

  private static boolean insideConditional(Node n) {
    return hasAncestorBeforeMethod(n,
                                   p
                                   -> p instanceof IfStmt ||
                                          p instanceof SwitchStmt ||
                                          p instanceof ConditionalExpr);
  }

  private static boolean hasAncestorBeforeMethod(Node n, Predicate<Node> test) {
    Node p = n.getParentNode().orElse(null);

    while (p != null && !(p instanceof MethodDeclaration)) {
      if (test.test(p)) {
        return true;
      }

      p = p.getParentNode().orElse(null);
    }

    return false;
  }

  private static String packageOf(CompilationUnit cu) {
    return cu.getPackageDeclaration()
        .map(p -> p.getNameAsString())
        .orElse("default");
  }

  private static String typeFqn(TypeDeclaration<?> t) {
    return t.getFullyQualifiedName().orElse(t.getNameAsString());
  }

  private static String methodFqn(MethodDeclaration m, String ownerFqn) {
    try {
      return m.resolve().getQualifiedSignature();
    } catch (Exception | StackOverflowError e) {
      // fall back to the signature as written in source
      String params = m.getParameters()
                          .stream()
                          .map(p -> p.getType().asString())
                          .collect(Collectors.joining(", "));

      return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
    }
  }

  private static String typeName(ClassOrInterfaceType t) {
    try {
      return t.resolve().asReferenceType().getQualifiedName();
    } catch (Exception | StackOverflowError e) {
      return t.getNameWithScope();
    }
  }

  private static String resolveTypeName(Type t) {
    try {
      return t.resolve().describe();
    } catch (Exception | StackOverflowError e) {
      return t.asString();
    }
  }

  private static <E> List<E> modifiers(NodeWithModifiers<?> n,
                                       Function<String, E> parse) {
    List<E> result = new ArrayList<>();

    for (Modifier m : n.getModifiers()) {
      try {
        result.add(parse.apply(m.getKeyword().name()));
      } catch (IllegalArgumentException e) {
        // modifier not represented in the schema
      }
    }

    return result;
  }
}
