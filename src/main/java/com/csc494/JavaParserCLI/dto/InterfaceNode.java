package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record InterfaceNode(String fqn, String name, String packageName,
                            List<InterfaceModifier> modifiers)
    implements ASGNode {}
