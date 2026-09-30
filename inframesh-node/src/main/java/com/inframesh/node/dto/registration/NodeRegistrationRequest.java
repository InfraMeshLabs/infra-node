package com.inframesh.node.dto.registration;

import com.inframesh.node.enums.NodeType;

/**
 * Sent by a Router or Worker to register itself with Console for the first time.
 *
 * Issuing, validating, and expiring the registration token are Console's
 * responsibility; infra-node only carries the value.
 */
public record NodeRegistrationRequest(
        /**
         * One-time token issued in advance by Console. Not reused after
         * registration succeeds.
         */
        String registrationToken,

        String name,
        NodeType nodeType
) {
}
