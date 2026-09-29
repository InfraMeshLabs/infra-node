package com.inframesh.node.dto.router;

import java.util.List;

public record RoutingHints(
        String preferredModel,
        String requiredProvider,
        List<String> requiredCapabilities,
        Boolean gpuRequired,
        Long minimumVramBytes,

        /**
         * When {@code true}, Router must not select a Worker that could send
         * this request's data to an EXTERNAL AI provider. Missing/null/false
         * are all equivalent to "no private routing restriction requested" —
         * see {@link #isPrivateData()}.
         */
        Boolean privateData
) {

    public RoutingHints {
        requiredCapabilities = requiredCapabilities == null
                ? List.of()
                : List.copyOf(requiredCapabilities);
    }

    public RoutingHints(String preferredModel, String requiredProvider) {
        this(preferredModel, requiredProvider, List.of(), null, null, null);
    }

    public boolean isPrivateData() {
        return Boolean.TRUE.equals(privateData);
    }
}
