package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record MethodNode(String fqn, String name, String returnTypeFqn,
                         List<String> parameterFqns, List<MethodModifier> modifiers)
    implements ASGNode {}
