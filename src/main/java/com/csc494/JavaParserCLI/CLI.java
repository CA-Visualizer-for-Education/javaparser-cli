package com.csc494.JavaParserCLI;

import com.csc494.JavaParserCLI.dto.CodebaseReport;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "jp-analyze", mixinStandardHelpOptions = true, version = "1.0",
    description =
        "Analyzes a Java codebase using JavaParser and prints JSON to stdout.")
public class CLI implements Callable<Integer> {
  @Option(names = {"-s", "--src"}, required = true,
          description = "Path to source directory or repo root.")
  private File sourceDir;

  @Override
  public Integer call() throws Exception {
    if (!sourceDir.exists() || !sourceDir.isDirectory()) {
      System.err.println("Error: Source path must be an existing directory.");
      return 1;
    }

    System.err.printf("Analyzing codebase at: %s%n",
                      sourceDir.getAbsolutePath());

    Analyzer analyzer = new Analyzer();
    CodebaseReport report = analyzer.analyze(sourceDir.toPath());

    ObjectMapper mapper =
        new ObjectMapper().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    mapper.writeValue(System.out, report);
    System.out.println();
    System.out.flush();

    return 0;
  }
}
