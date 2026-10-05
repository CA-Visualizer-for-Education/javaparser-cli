package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record FieldNode(String fqn, String name, String typeFqn,
                        List<FieldModifier> modifiers) implements ASGNode {}
