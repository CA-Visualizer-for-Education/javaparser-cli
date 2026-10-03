package com.csc494.JavaParserCLI.dto;

import java.util.ArrayList;
import java.util.List;

public class MethodInfo {
  public String signature;
  public List<String> calls = new ArrayList<>();
}
