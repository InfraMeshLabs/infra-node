package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeError;

import java.util.Objects;

/**
 * Thrown from a {@link NodeRequestHandler} or {@link NodeStreamRequestHandler} to fail the request
 * with a specific {@link NodeError#code()} instead of {@link NodeError#REQUEST_FAILED}.
 * See {@link NodeErrorCarrier}.
 */
public class NodeRequestException extends RuntimeException implements NodeErrorCarrier {

    private final String code;

    public NodeRequestException(String code, String message) {
        this(code, message, null);
    }

    public NodeRequestException(String code, String message, Throwable cause) {
        super(message, cause);
        if (Objects.requireNonNull(code, "code").isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @Override
    public NodeError nodeError() {
        return new NodeError(code, getMessage());
    }
}
