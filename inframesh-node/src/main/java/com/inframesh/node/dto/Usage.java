package com.inframesh.node.dto;

public record Usage(
        Long promptTokens,
        Long completionTokens,
        Long totalTokens
) {
}
