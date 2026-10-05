package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record EnumNode(String fqn, String name, String packageName,
                       List<String> constants) implements ASGNode {}
