package com.csc494.JavaParserCLI.dto;

import java.util.ArrayList;
import java.util.List;

public class ClassInfo {
  public String packageName;
  public String className;
  public TypeKind type;
  public int methodCount;
  public List<MethodInfo> methods = new ArrayList<>();
  public List<String> fields = new ArrayList<>();
}
