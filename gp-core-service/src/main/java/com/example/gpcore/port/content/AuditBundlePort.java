package com.example.gpcore.port.content;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.gateway.model.AuditItem;

import java.util.List;

/**
 * CONTENT port for audit/export bundle assembly. The gateway authorizes the
 * scope FIRST (EXPORT action); a hidden scope means {@link #items(NodeRef)} is
 * NEVER called, avoiding hidden-scope enumeration and bundle-membership leaks
 * (Codex 019f1913 #10). Returned items are then filtered item-by-item.
 *
 * <p>Content port: injectable ONLY by the Read Gateway implementation.
 */
public interface AuditBundlePort {

    List<AuditItem> items(NodeRef scope);
}
