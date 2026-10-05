package com.csc494.JavaParserCLI.dto;

public record HasMethodEdge(String ownerFqn, String methodFqn)
    implements ASGEdge {}
