package com.csc494.JavaParserCLI.dto;

public record MutatesFieldEdge(String methodFqn, String mutatedFieldFqn,
                               MutatingOperation operation) implements ASGEdge {
}
