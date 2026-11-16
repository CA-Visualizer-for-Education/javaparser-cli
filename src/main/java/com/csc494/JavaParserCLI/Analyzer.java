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
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.github.javaparser.utils.SourceRoot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
  private final List<ImplementationLink> implementationLinks =
      new ArrayList<>();
  private final List<TypeDeclaration<?>> sourceTypes = new ArrayList<>();

  public CodebaseReport analyze(Path sourceDir) throws Exception {
    nodes.clear();
    edges.clear();
    implementationLinks.clear();
    sourceTypes.clear();

    // Symbol solver lets us resolve calls and types to fully qualified names
    CombinedTypeSolver typeSolver = new CombinedTypeSolver();
    typeSolver.add(new ReflectionTypeSolver());
    ParserConfiguration config =
        new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
            .setSymbolResolver(new JavaSymbolSolver(typeSolver));
    typeSolver.add(new JavaParserTypeSolver(sourceDir, config));
    SourceRoot sourceRoot = new SourceRoot(sourceDir, config);

    var parseResults = sourceRoot.tryToParse();

    parseResults.stream()
        .filter(result -> !result.isSuccessful())
        .forEach(result
                 -> System.err.printf("Warning: Unable to parse source: %s%n",
                                      result.getProblems()));
    List<CompilationUnit> cus =
        parseResults.stream()
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

            n.getExtendedTypes().forEach(t -> addExtends(t, fqn));
          } else {
            // ClassNode + EXTENDS / IMPLEMENTS edges
            nodes.add(new ClassNode(fqn, n.getNameAsString(), pkg,
                                    modifiers(n, ClassModifier::valueOf),
                                    supertypeFqns(n)));

            n.getExtendedTypes().forEach(t -> addExtends(t, fqn));
            n.getImplementedTypes().forEach(t -> addImplementation(t, fqn, n));
          }

          sourceTypes.add(n);
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

          n.getImplementedTypes().forEach(t -> addImplementation(t, fqn, n));

          sourceTypes.add(n);
          processMembers(n, fqn);
        }

        @Override
        public void visit(RecordDeclaration n, Void arg) {
          super.visit(n, arg);
          String fqn = typeFqn(n);

          List<ClassModifier> mods = modifiers(n, ClassModifier::valueOf);

          if (!mods.contains(ClassModifier.FINAL)) {
            mods.add(ClassModifier.FINAL);
          }

          nodes.add(new ClassNode(fqn, n.getNameAsString(), packageOf(cu), mods,
                                  supertypeFqns(n)));

          n.getImplementedTypes().forEach(t -> addImplementation(t, fqn, n));
          sourceTypes.add(n);
          processMembers(n, fqn);

          n.getParameters().forEach(parameter -> {
            String fieldFqn = fqn + "." + parameter.getNameAsString();
            nodes.add(new FieldNode(
                fieldFqn, parameter.getNameAsString(),
                resolveTypeName(parameter.getType()),
                List.of(FieldModifier.PRIVATE, FieldModifier.FINAL)));
            edges.add(new HasFieldEdge(fqn, fieldFqn));
          });

          try {
            for (ResolvedMethodDeclaration method :
                 n.resolve().getDeclaredMethods()) {
              ensureMethodNode(method);
              ensureHasMethodEdge(fqn, method.getQualifiedSignature(), false);
            }
          } catch (Exception | StackOverflowError e) {
            System.err.printf(
                "Warning: Unable to resolve record methods for '%s': %s%n", fqn,
                e);
          }
        }
      }, null);
    }

    emitInheritedMethods();
    emitMethodImplementations();

    return new CodebaseReport(new ArrayList<>(nodes), new ArrayList<>(edges));
  }

  private void addExtends(ClassOrInterfaceType parentType, String childFqn) {
    try {
      String parentFqn =
          parentType.resolve().asReferenceType().getQualifiedName();

      edges.add(new ExtendsEdge(parentFqn, childFqn));
    } catch (Exception | StackOverflowError e) {
      System.err.printf(
          "Warning: Unable to resolve parent type '%s' of '%s'; skipping EXTENDS relationship.%n",
          parentType, childFqn);
    }
  }

  private static List<String> supertypeFqns(TypeDeclaration<?> type) {
    try {
      return resolveTypeDeclaration(type)
          .getAllAncestors()
          .stream()
          .map(ResolvedReferenceType::getQualifiedName)
          .distinct()
          .collect(Collectors.toList());
    } catch (Exception | StackOverflowError e) {
      System.err.printf("Warning: Unable to resolve supertypes for '%s'.%n",
                        type.getNameAsString());
      return List.of();
    }
  }

  private void addImplementation(ClassOrInterfaceType interfaceType,
                                 String implementingFqn,
                                 TypeDeclaration<?> implementingType) {
    try {
      ResolvedReferenceType resolvedInterfaceType =
          interfaceType.resolve().asReferenceType();
      ResolvedReferenceTypeDeclaration interfaceDeclaration =
          resolvedInterfaceType.getTypeDeclaration().orElseThrow();
      ResolvedReferenceTypeDeclaration implementingDeclaration =
          resolveTypeDeclaration(implementingType);

      if (!interfaceDeclaration.isInterface()) {
        System.err.printf(
            "Warning: %s is not an interface; skipping IMPLEMENTS relationship.%n",
            interfaceType);
        return;
      }

      String interfaceFqn = interfaceDeclaration.getQualifiedName();
      int edgeIndex = edges.size();
      edges.add(new ImplementsEdge(interfaceFqn, implementingFqn, List.of()));
      implementationLinks.add(new ImplementationLink(
          edgeIndex, interfaceFqn, implementingFqn, resolvedInterfaceType,
          implementingDeclaration));
    } catch (Exception | StackOverflowError e) {
      System.err.printf(
          "Warning: Unable to resolve implemented type '%s' on '%s'; skipping IMPLEMENTS relationship.%n",
          interfaceType, implementingFqn);
    }
  }

  private void emitInheritedMethods() {
    for (TypeDeclaration<?> sourceType : sourceTypes) {
      String ownerFqn = typeFqn(sourceType);
      try {
        ResolvedReferenceTypeDeclaration owner =
            resolveTypeDeclaration(sourceType);
        List<MethodUsage> methods = new ArrayList<>(methodsInHierarchy(owner));
        for (MethodUsage usage : methods) {
          try {
            ResolvedMethodDeclaration method = usage.getDeclaration();
            if (!isPublicInstanceMethod(method) ||
                method.declaringType().getQualifiedName().equals(ownerFqn) ||
                isOverriddenBy(usage, methods)) {
              continue;
            }

            String methodFqn = method.getQualifiedSignature();
            ensureMethodNode(method);
            ensureHasMethodEdge(ownerFqn, methodFqn, true);
          } catch (Exception | StackOverflowError e) {
            System.err.printf(
                "Warning: Unable to resolve inherited method '%s' for '%s': %s%n",
                usage.getName(), ownerFqn, e);
          }
        }
      } catch (Exception | StackOverflowError e) {
        System.err.printf(
            "Warning: Unable to resolve inherited methods for '%s': %s%n",
            ownerFqn, e);
      }
    }
  }

  private void emitMethodImplementations() {
    for (ImplementationLink link : implementationLinks) {
      List<MethodImplementation> mappings = new ArrayList<>();
      try {
        Set<MethodUsage> classMethods =
            methodsInHierarchy(link.implementingDeclaration());
        Set<MethodUsage> interfaceMethods =
            new LinkedHashSet<>(link.interfaceType().getDeclaredMethods());
        for (ResolvedReferenceType ancestor :
             link.interfaceType().getAllAncestors()) {
          interfaceMethods.addAll(ancestor.getDeclaredMethods());
        }
        List<MethodUsage> effectiveInterfaceMethods =
            new ArrayList<>(interfaceMethods);
        for (MethodUsage interfaceUsage : effectiveInterfaceMethods) {
          try {
            if (isOverriddenBy(interfaceUsage, effectiveInterfaceMethods)) {
              continue;
            }
            ResolvedMethodDeclaration interfaceMethod =
                interfaceUsage.getDeclaration();
            if (!isPublicInstanceMethod(interfaceMethod)) {
              continue;
            }

            List<ResolvedReferenceType> ancestors =
                link.implementingDeclaration().getAllAncestors();
            Optional<MethodUsage> implementation =
                classMethods.stream()
                    .filter(
                        candidate
                        -> !candidate.getDeclaration().isStatic() &&
                               candidate.isSubSignature(interfaceUsage) &&
                               returnTypeCompatible(candidate, interfaceUsage))
                    .min(Comparator.comparingInt(candidate -> {
                      ResolvedReferenceTypeDeclaration declaringType =
                          candidate.getDeclaration().declaringType();
                      String declaringFqn = declaringType.getQualifiedName();
                      if (declaringFqn.equals(link.implementingClassFqn())) {
                        return 0;
                      }
                      for (int i = 0; i < ancestors.size(); i++) {
                        ResolvedReferenceType ancestor = ancestors.get(i);
                        if (ancestor.getQualifiedName().equals(declaringFqn)) {
                          return i + (declaringType.isInterface() ? 1000 : 1);
                        }
                      }
                      return Integer.MAX_VALUE;
                    }));
            if (implementation.isEmpty() ||
                !isPublicInstanceMethod(
                    implementation.get().getDeclaration()) ||
                implementation.get().getDeclaration().isAbstract()) {
              continue;
            }

            ResolvedMethodDeclaration implementationMethod =
                implementation.get().getDeclaration();
            String interfaceMethodFqn = interfaceMethod.getQualifiedSignature();
            String implementationMethodFqn =
                implementationMethod.getQualifiedSignature();

            ensureMethodNode(interfaceMethod);
            ensureMethodNode(implementationMethod);
            ensureHasMethodEdge(
                link.interfaceFqn(), interfaceMethodFqn,
                !interfaceMethod.declaringType().getQualifiedName().equals(
                    link.interfaceFqn()));
            ensureHasMethodEdge(
                link.implementingClassFqn(), implementationMethodFqn,
                !implementationMethod.declaringType().getQualifiedName().equals(
                    link.implementingClassFqn()));
            mappings.add(new MethodImplementation(interfaceMethodFqn,
                                                  implementationMethodFqn));
          } catch (Exception | StackOverflowError e) {
            System.err.printf(
                "Warning: Unable to resolve implementation of '%s' from '%s' on '%s': %s%n",
                interfaceUsage.getName(), link.interfaceFqn(),
                link.implementingClassFqn(), e);
          }
        }
      } catch (Exception | StackOverflowError e) {
        System.err.printf(
            "Warning: Unable to resolve method implementations for '%s' on '%s': %s%n",
            link.interfaceFqn(), link.implementingClassFqn(), e);
      }

      edges.set(link.edgeIndex(),
                new ImplementsEdge(link.interfaceFqn(),
                                   link.implementingClassFqn(), mappings));
    }
  }

  private void ensureMethodNode(ResolvedMethodDeclaration method) {
    String methodFqn = method.getQualifiedSignature();
    boolean exists = nodes.stream()
                         .filter(MethodNode.class ::isInstance)
                         .map(MethodNode.class ::cast)
                         .anyMatch(node -> node.fqn().equals(methodFqn));
    if (exists) {
      return;
    }

    List<String> params = method.formalParameterTypes()
                              .stream()
                              .map(paramType -> paramType.describe())
                              .collect(Collectors.toList());
    List<MethodModifier> methodModifiers = new ArrayList<>();
    String access = method.accessSpecifier().name();
    try {
      methodModifiers.add(MethodModifier.valueOf(access));
    } catch (IllegalArgumentException ignored) {
      // No access modifier is represented in the schema.
    }
    if (method.isStatic()) {
      methodModifiers.add(MethodModifier.STATIC);
    }
    if (method.isAbstract()) {
      methodModifiers.add(MethodModifier.ABSTRACT);
    }
    method.toAst(MethodDeclaration.class).ifPresent(ast -> {
      for (Modifier modifier : ast.getModifiers()) {
        if (modifier.getKeyword() == Modifier.Keyword.FINAL &&
            !methodModifiers.contains(MethodModifier.FINAL)) {
          methodModifiers.add(MethodModifier.FINAL);
        }
      }
    });

    nodes.add(new MethodNode(methodFqn, method.getName(),
                             method.getReturnType().describe(), params,
                             methodModifiers));
  }

  private void ensureHasMethodEdge(String ownerFqn, String methodFqn,
                                   boolean inherited) {
    boolean exists = edges.stream()
                         .filter(HasMethodEdge.class ::isInstance)
                         .map(HasMethodEdge.class ::cast)
                         .anyMatch(edge
                                   -> edge.ownerFqn().equals(ownerFqn) &&
                                          edge.methodFqn().equals(methodFqn));
    if (!exists) {
      edges.add(new HasMethodEdge(ownerFqn, methodFqn, inherited));
    }
  }

  private static boolean isOverriddenBy(MethodUsage method,
                                        List<MethodUsage> methods) {
    ResolvedReferenceTypeDeclaration declaringType =
        method.getDeclaration().declaringType();
    return methods.stream().anyMatch(candidate -> {
      ResolvedMethodDeclaration candidateDeclaration =
          candidate.getDeclaration();
      return candidate != method &&
          !declaringType.getQualifiedName().equals(
              candidateDeclaration.declaringType().getQualifiedName()) &&
          isPublicInstanceMethod(candidateDeclaration) &&
          candidate.isSubSignature(method) &&
          declaringType.isAssignableBy(candidateDeclaration.declaringType()) &&
          returnTypeCompatible(candidate, method);
    });
  }

  private static boolean returnTypeCompatible(MethodUsage implementation,
                                              MethodUsage overridden) {
    ResolvedType actual = implementation.returnType();
    ResolvedType expected = overridden.returnType();
    if (actual.isVoid() || expected.isVoid() || actual.isPrimitive() ||
        expected.isPrimitive()) {
      return actual.describe().equals(expected.describe());
    }
    return expected.isAssignableBy(actual);
  }

  private static boolean
  isPublicInstanceMethod(ResolvedMethodDeclaration method) {
    if (method.isStatic()) {
      return false;
    }
    if (method.declaringType().isInterface()) {
      return method.accessSpecifier() !=
          com.github.javaparser.ast.AccessSpecifier.PRIVATE;
    }
    return method.accessSpecifier() ==
        com.github.javaparser.ast.AccessSpecifier.PUBLIC;
  }

  private static Set<MethodUsage>
  methodsInHierarchy(ResolvedReferenceTypeDeclaration type) {
    Set<MethodUsage> methods = new LinkedHashSet<>();
    type.getDeclaredMethods()
        .stream()
        .map(MethodUsage::new)
        .forEach(methods::add);
    for (ResolvedReferenceType ancestor : type.getAllAncestors()) {
      methods.addAll(ancestor.getDeclaredMethods());
    }
    return methods;
  }

  private static ResolvedReferenceTypeDeclaration
  resolveTypeDeclaration(TypeDeclaration<?> type) {
    if (type instanceof ClassOrInterfaceDeclaration declaration) {
      return declaration.resolve();
    }
    if (type instanceof EnumDeclaration declaration) {
      return declaration.resolve();
    }
    if (type instanceof RecordDeclaration declaration) {
      return declaration.resolve();
    }
    throw new IllegalArgumentException("Unsupported type declaration");
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
      List<String> params = method.getParameters()
                                .stream()
                                .map(p -> resolveTypeName(p.getType()))
                                .collect(Collectors.toList());

      nodes.add(new MethodNode(methodFqn, method.getNameAsString(),
                               resolveTypeName(method.getType()), params,
                               modifiers(method, MethodModifier::valueOf)));
      edges.add(new HasMethodEdge(ownerFqn, methodFqn, false));

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
          ResolvedMethodDeclaration resolved = call.resolve();
          String called = resolved.getQualifiedSignature();
          Optional<Expression> scope = call.getScope();
          String target =
              scope.map(Analyzer.this::targetObjectFqn).orElse(null);
          if (called.isBlank() || (scope.isPresent() && target == null)) {
            System.err.printf(
                "Warning: Unable to resolve complete method call '%s' in '%s'; skipping CALLS relationship.%n",
                call, methodFqn);
            return;
          }

          edges.add(new CallsEdge(methodFqn, called, target, insideLoop(call),
                                  insideConditional(call), false,
                                  resolved.isStatic()));
        } catch (Exception | StackOverflowError e) {
          System.err.printf(
              "Warning: Unable to resolve method call '%s' in '%s'; skipping CALLS relationship: %s%n",
              call, methodFqn, e);
        }
      }

      // CALLS edge for a constructor call (`new Foo(...)`)
      @Override
      public void visit(ObjectCreationExpr call, Void arg) {
        super.visit(call, arg);

        try {
          String called = call.resolve().getQualifiedSignature();
          if (called.isBlank()) {
            System.err.printf(
                "Warning: Unable to resolve constructor call '%s' in '%s'; skipping CALLS relationship.%n",
                call, methodFqn);
            return;
          }

          edges.add(new CallsEdge(methodFqn, called, null, insideLoop(call),
                                  insideConditional(call), true, false));
        } catch (Exception | StackOverflowError e) {
          System.err.printf(
              "Warning: Unable to resolve constructor call '%s' in '%s'; skipping CALLS relationship: %s%n",
              call, methodFqn, e);
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

  /**
   * Declaration of the direct receiver, or its resolved type if not a field.
   */
  private String targetObjectFqn(Expression scope) {
    try {
      if (scope instanceof NameExpr name) {
        try {
          ResolvedValueDeclaration declaration = name.resolve();
          if (declaration.isField()) {
            var field = declaration.asField();
            return field.declaringType().getQualifiedName() + "." +
                field.getName();
          }
          if (declaration.isType()) {
            return declaration.asType().getQualifiedName();
          }
        } catch (Exception | StackOverflowError ignored) {
          // A type qualifier is not a value declaration.
        }
      } else if (scope instanceof FieldAccessExpr fieldAccess) {
        ResolvedValueDeclaration declaration = fieldAccess.resolve();
        if (declaration.isField()) {
          var field = declaration.asField();
          return field.declaringType().getQualifiedName() + "." +
              field.getName();
        }
      }
      ResolvedType type = scope.calculateResolvedType();
      if (type.isConstraint()) {
        type = type.asConstraintType().getBound();
      }
      return type.isReferenceType() ? type.asReferenceType().getQualifiedName()
                                    : type.describe();
    } catch (Exception | StackOverflowError e) {
      System.err.printf("Warning: Unable to resolve call receiver '%s': %s%n",
                        scope, e);
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
    try {
      return resolveTypeDeclaration(t).getQualifiedName();
    } catch (Exception | StackOverflowError e) {
      return t.getFullyQualifiedName().orElse(t.getNameAsString());
    }
  }

  private static String methodFqn(MethodDeclaration m, String ownerFqn) {
    try {
      return m.resolve().getQualifiedSignature();
    } catch (Exception | StackOverflowError e) {
      // fall back to the signature as written in source
      String params = m.getParameters()
                          .stream()
                          .map(p -> resolveTypeName(p.getType()))
                          .collect(Collectors.joining(", "));

      return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
    }
  }

  private static String resolveTypeName(Type t) {
    try {
      return t.resolve().describe();
    } catch (Exception | StackOverflowError e) {
      return t.asString();
    }
  }

  private record
  ImplementationLink(int edgeIndex, String interfaceFqn,
                     String implementingClassFqn,
                     ResolvedReferenceType interfaceType,
                     ResolvedReferenceTypeDeclaration implementingDeclaration) {
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
