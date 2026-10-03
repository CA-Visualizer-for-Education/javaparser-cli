package com.csc494.JavaParserCLI;

import com.csc494.JavaParserCLI.dto.ClassInfo;
import com.csc494.JavaParserCLI.dto.CodebaseReport;
import com.csc494.JavaParserCLI.dto.MethodInfo;
import com.csc494.JavaParserCLI.dto.TypeKind;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import java.nio.file.Path;
import java.util.List;

public class Analyzer {

  public CodebaseReport analyze(Path sourceDir) throws Exception {

    // configure symbol solver
    CombinedTypeSolver typeSolver = new CombinedTypeSolver();
    typeSolver.add(new ReflectionTypeSolver());
    typeSolver.add(new JavaParserTypeSolver(sourceDir));

    ParserConfiguration parserConfiguration =
        new ParserConfiguration().setSymbolResolver(
            new JavaSymbolSolver(typeSolver));

    SourceRoot sourceRoot = new SourceRoot(sourceDir, parserConfiguration);

    CodebaseReport report = new CodebaseReport();

    // parse all Java files under sourceDir
    List<CompilationUnit> cus =
        sourceRoot.tryToParseParallelized()
            .stream()
            .filter(res -> res.isSuccessful() && res.getResult().isPresent())
            .map(res -> res.getResult().get())
            .toList();

    report.totalFiles = cus.size();

    for (CompilationUnit cu : cus) {
      cu.accept(new VoidVisitorAdapter<Void>() {
        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
          super.visit(n, arg);

          ClassInfo info = new ClassInfo();

          info.packageName = cu.getPackageDeclaration()
                                 .map(p -> p.getNameAsString())
                                 .orElse("default");

          info.className = n.getNameAsString();
          info.type = n.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS;

          if (n.isInterface()) {
            report.totalInterfaces++;
          } else {
            report.totalClasses++;
          }

          // analyze each method
          for (MethodDeclaration method : n.getMethods()) {
            MethodInfo methodInfo = new MethodInfo();

            methodInfo.signature =
                method.getDeclarationAsString(false, false, false);

            // find all method calls inside this method
            method.accept(new VoidVisitorAdapter<Void>() {
              @Override
              public void visit(MethodCallExpr call, Void arg) {
                super.visit(call, arg);

                try {
                  var resolved = call.resolve();

                  methodInfo.calls.add(resolved.getQualifiedSignature());

                } catch (Exception e) {
                  // could not resolve this method call
                }
              }
            }, null);

            info.methods.add(methodInfo);
          }

          info.methodCount = info.methods.size();

          // collect fields
          n.getFields().forEach(
              field
              -> field.getVariables().forEach(
                  variable -> info.fields.add(variable.getNameAsString())));

          report.classes.add(info);
        }
      }, null);
    }

    return report;
  }
}
