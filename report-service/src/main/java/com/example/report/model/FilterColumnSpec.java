package com.example.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FilterColumnSpec(
    String expression,
    String join,
    String paramType
) {}
