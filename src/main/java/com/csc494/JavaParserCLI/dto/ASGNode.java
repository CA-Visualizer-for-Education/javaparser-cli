package com.csc494.JavaParserCLI.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
              property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ClassNode.class, name = "CLASS"),
  @JsonSubTypes.Type(value = InterfaceNode.class, name = "INTERFACE"),
  @JsonSubTypes.Type(value = EnumNode.class, name = "ENUM"),
  @JsonSubTypes.Type(value = MethodNode.class, name = "METHOD"),
  @JsonSubTypes.Type(value = FieldNode.class, name = "FIELD")
})
public sealed interface ASGNode permits ClassNode, InterfaceNode, EnumNode,
    MethodNode, FieldNode {}
