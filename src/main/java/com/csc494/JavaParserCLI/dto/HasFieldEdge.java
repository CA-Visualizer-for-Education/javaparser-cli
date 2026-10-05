package com.csc494.JavaParserCLI.dto;

public record HasFieldEdge(String ownerFqn, String fieldFqn)
    implements ASGEdge {}
