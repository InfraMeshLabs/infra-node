package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeErrorCarrierTest {

    @Test
    void sendsTheCarriersOwnError() {
        assertThat(NodeErrorCarrier.toNodeError(new NodeRequestException("SOME_CODE", "safe message")))
                .isEqualTo(new NodeError("SOME_CODE", "safe message"));
    }

    @Test
    void sendsRequestFailedForAnyOtherException() {
        assertThat(NodeErrorCarrier.toNodeError(new IllegalStateException("boom")))
                .isEqualTo(new NodeError(NodeError.REQUEST_FAILED, "boom"));
    }

    @Test
    void fallsBackToRequestFailedWhenTheCarrierGivesNoCode() {
        class NoCode extends RuntimeException implements NodeErrorCarrier {
            NoCode() {
                super("no code");
            }

            @Override
            public NodeError nodeError() {
                return new NodeError(" ", "ignored");
            }
        }

        assertThat(NodeErrorCarrier.toNodeError(new NoCode()))
                .isEqualTo(new NodeError(NodeError.REQUEST_FAILED, "no code"));
    }

    @Test
    void refusesABlankCode() {
        assertThatThrownBy(() -> new NodeRequestException(" ", "message")).isInstanceOf(IllegalArgumentException.class);
    }
}
