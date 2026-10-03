package com.csc494.JavaParserCLI.dto;

import java.util.ArrayList;
import java.util.List;

public class CodebaseReport {
  public int totalFiles;
  public int totalClasses;
  public int totalInterfaces;
  public List<ClassInfo> classes = new ArrayList<>();
}
