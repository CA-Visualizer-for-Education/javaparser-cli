package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record ImplementsEdge(String interfaceFqn, String implementingClassFqn,
                             List<MethodImplementation> methodImplementations)
    implements ASGEdge {}
