package com.csc494.JavaParserCLI.dto;

import java.util.List;

public record CodebaseReport(List<ASGNode> nodes, List<ASGEdge> edges) {}
