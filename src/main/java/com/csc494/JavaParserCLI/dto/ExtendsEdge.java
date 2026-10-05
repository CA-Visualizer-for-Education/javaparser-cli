package com.csc494.JavaParserCLI.dto;

public record ExtendsEdge(String parentClassFqn, String childClassFqn)
    implements ASGEdge {}
