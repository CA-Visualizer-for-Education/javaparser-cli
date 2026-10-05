package com.csc494.JavaParserCLI.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
              property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ExtendsEdge.class, name = "EXTENDS")
  , @JsonSubTypes.Type(value = ImplementsEdge.class, name = "IMPLEMENTS"),
      @JsonSubTypes.Type(value = HasFieldEdge.class, name = "HAS_FIELD"),
      @JsonSubTypes.Type(value = HasMethodEdge.class, name = "HAS_METHOD"),
      @JsonSubTypes.Type(value = CallsEdge.class, name = "CALLS"),
      @JsonSubTypes.Type(value = MutatesFieldEdge.class, name = "MUTATES_FIELD")
})
public sealed interface ASGEdge permits ExtendsEdge,
    ImplementsEdge, HasFieldEdge, HasMethodEdge, CallsEdge, MutatesFieldEdge {}
