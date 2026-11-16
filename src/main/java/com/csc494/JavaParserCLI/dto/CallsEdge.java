package com.csc494.JavaParserCLI.dto;

public record
CallsEdge(String callingMethodFqn, String calledMethodFqn,
          String targetObjectFqn, // was this called on an existing object, and
                                  // if so which one
          boolean insideLoop, boolean insideConditional,
          boolean isConstructorCall,
          boolean isStaticCall) implements ASGEdge {}
