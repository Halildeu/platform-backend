package com.example.apigateway.telemetry;

import org.springframework.stereotype.Component;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2 absorb): bounded
 * route-group classifier for gateway auth telemetry. Maps an incoming
 * request path to a low-cardinality enum value used as a Micrometer
 * counter tag.
 *
 * <p>Cardinality contract: the returned set is finite and known at
 * compile time. URL ids, slugs, query strings, and tenant variants
 * all collapse into a single bucket. {@code unknown} is the catch-all
 * for paths that don't match any classifier branch.
 *
 * <p>Path family coverage:
 * <ul>
 *   <li>K8s prod (gateway routed via Spring Cloud Gateway): {@code /users},
 *       {@code /reports}, {@code /schemas}, {@code /notify}, etc.</li>
 *   <li>Local/v1 (legacy + new): {@code /api/users}, {@code /api/v1/users},
 *       {@code /api/v1/reports}, etc.</li>
 *   <li>Auth surface: {@code /api/auth/cookie} root + refresh,
 *       {@code /api/v1/auth/sessions}, {@code /api/v1/authz/me}</li>
 *   <li>Auth metadata: {@code /silent-check-sso.html}, {@code /.well-known/...}</li>
 * </ul>
 */
@Component
public class GatewayRouteClassifier {

  /**
   * Classify the given path into a bounded route group enum value.
   * Never returns {@code null}; unknown paths return {@code "unknown"}.
   *
   * <p>Order matters: more specific patterns are checked first so
   * {@code /api/auth/cookie/refresh} doesn't collapse into the
   * {@code /api/auth/cookie} root bucket.
   */
  public String classify(String path) {
    if (path == null || path.isEmpty()) {
      return "unknown";
    }

    // Auth surface (specific before general)
    if (path.equals("/api/auth/cookie/refresh")) {
      return "auth_cookie_refresh";
    }
    if (path.equals("/api/auth/cookie")) {
      return "auth_cookie";
    }
    if (path.startsWith("/api/v1/auth/sessions")) {
      return "auth_sessions";
    }
    if (path.startsWith("/api/v1/authz/me")) {
      return "authz_me";
    }

    // Domain endpoints (K8s prod + local/v1 alias)
    if (path.matches("^/api(/v1)?/users(/.*)?$") || path.startsWith("/users")) {
      return "users";
    }
    if (path.matches("^/api(/v1)?/reports(/.*)?$") || path.startsWith("/reports")) {
      return "reports";
    }
    if (path.matches("^/api(/v1)?/schemas(/.*)?$") || path.startsWith("/schemas")) {
      return "schemas";
    }
    if (path.matches("^/api(/v1)?/notify(/.*)?$") || path.startsWith("/notify")) {
      return "notify";
    }
    if (path.matches("^/api(/v1)?/theme-registry(/.*)?$") || path.startsWith("/theme-registry")) {
      return "theme_registry";
    }
    if (path.matches("^/api(/v1)?/audit(/.*)?$") || path.startsWith("/audit")) {
      return "audit";
    }
    if (path.matches("^/api(/v1)?/access(/.*)?$") || path.startsWith("/access")) {
      return "access";
    }
    if (path.matches("^/api(/v1)?/variants(/.*)?$") || path.startsWith("/variants")) {
      return "variants";
    }
    if (path.matches("^/api(/v1)?/admin(/.*)?$") || path.startsWith("/admin")) {
      return "admin";
    }

    // Auth metadata
    if (path.startsWith("/silent-check-sso") || path.startsWith("/.well-known")) {
      return "auth_meta";
    }

    return "unknown";
  }
}
