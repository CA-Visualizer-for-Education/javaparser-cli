package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record ClassNode(String fqn, String name, String packageName,
                        List<ClassModifier> modifiers,
                        List<String> supertypeFqns) implements ASGNode {}
