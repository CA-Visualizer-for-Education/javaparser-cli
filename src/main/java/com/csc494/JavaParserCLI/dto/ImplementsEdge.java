package com.csc494.JavaParserCLI.dto;

public record ImplementsEdge(String interfaceFqn, String implementingClassFqn)
    implements ASGEdge {}
