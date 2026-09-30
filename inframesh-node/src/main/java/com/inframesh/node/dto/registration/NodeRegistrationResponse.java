package com.inframesh.node.dto.registration;

import java.util.UUID;

/**
 * Returned by Console after a successful registration.
 *
 * The node authenticates later connections with {@code nodeId} and
 * {@code credential} instead of the registration token. Credential
 * issuance, hashing, rotation, and revocation are Console's responsibility.
 */
public record NodeRegistrationResponse(
        /**
         * Immutable external identity of the node, separate from any
         * Console database key.
         */
        UUID nodeId,

        String credential
) {
}
