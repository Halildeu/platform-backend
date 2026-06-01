package com.example.report.execution;

import java.util.Optional;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.stereotype.Component;

/**
 * Maps {@link RemoteReportRequest} to downstream service query params
 * according to the declared {@code requestShape} (PR-D2.1c1).
 *
 * <p>Codex 019e8306 iter-2: request normalizer is critical because
 * dynamic-report frontend ↔ backend protocol differs from each
 * downstream service's protocol. Example: user-service expects
 * {@code sort=field,dir;...} but dynamic-report uses JSON
 * {@code sort=[{colId,sort}]}.
 *
 * <p>Supported shapes (PR-D2.1c1 initial set):
 * <ul>
 *   <li>{@code style-api-paged-v1} — user-service / permission-service
 *       style: {@code page}, {@code pageSize}, {@code search}, {@code sort}
 *       (comma+semicolon), advancedFilter passthrough as individual
 *       query params keyed by the colId.</li>
 * </ul>
 *
 * <p>Future shapes (not yet implemented): {@code style-api-paged-v2},
 * {@code audit-events-v1}, {@code aggregation-mart-v1}.
 */
@Component
public class RemoteRequestNormalizer {

    public static final String SHAPE_STYLE_API_PAGED_V1 = "style-api-paged-v1";

    /**
     * Translate a normalized {@link RemoteReportRequest} into downstream
     * query params per the declared shape.
     *
     * @return MultiValueMap of query params; the executor appends these
     *         to the configured base-url + path.
     * @throws IllegalArgumentException if the shape is not recognized.
     */
    public MultiValueMap<String, String> toQueryParams(
            String requestShape, RemoteReportRequest request) {
        if (requestShape == null || requestShape.isBlank()) {
            throw new IllegalArgumentException("requestShape must not be blank");
        }
        return switch (requestShape) {
            case SHAPE_STYLE_API_PAGED_V1 -> toStyleApiPagedV1(request);
            default -> throw new IllegalArgumentException(
                    "Unknown requestShape: " + requestShape
                            + " (supported: " + SHAPE_STYLE_API_PAGED_V1 + ")");
        };
    }

    private MultiValueMap<String, String> toStyleApiPagedV1(RemoteReportRequest request) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();

        // Required: page + pageSize
        params.add("page", String.valueOf(request.page()));
        params.add("pageSize", String.valueOf(request.pageSize()));

        // Optional: search (free-text)
        Optional.ofNullable(request.search())
                .filter(s -> !s.isBlank())
                .ifPresent(s -> params.add("search", s));

        // Sort: AG-Grid [{colId, sort}, ...] → "field,dir;field2,dir2"
        if (!request.sort().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (RemoteReportRequest.SortEntry entry : request.sort()) {
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append(entry.colId()).append(",").append(entry.sort().toLowerCase());
            }
            params.add("sort", sb.toString());
        }

        // Advanced filter: pass through key/value pairs as individual params.
        // Downstream service is responsible for allowlisting which fields it
        // honors; report-service does not enforce field allowlist here (the
        // ExecutionConfig allowlist already gates which downstream paths
        // can be called).
        request.advancedFilter().forEach((key, value) -> {
            if (value != null) {
                params.add(key, String.valueOf(value));
            }
        });

        return params;
    }
}
