# permission.api.md — v1 & Legacy

## v1 REST Path (preferred)

- `POST /api/v1/permissions/check` — body `PermissionCheckRequestDto`, returns `{ allowed: boolean }`
- `POST /api/v1/permissions/assignments` — create assignment, body `PermissionAssignRequestDto`, returns `PermissionAssignmentDto`
- `PATCH /api/v1/permissions/assignments` — update assignment, body `PermissionAssignmentUpdateRequestDto`, returns `PermissionAssignmentDto`
- `DELETE /api/v1/permissions/assignments/{assignmentId}?performedBy=` — returns `{ status, auditId }`
- `GET /api/v1/permissions/assignments?userId=&companyId=&projectId=&warehouseId=` — returns `PagedResultDto<PermissionAssignmentDto>`

- `GET /api/v1/roles` — returns `PagedResultDto<RoleDto>`
- `POST /api/v1/roles/{roleId}/clone` — body `CloneRoleRequestDto`, returns `{ role, auditId }`
- `PATCH /api/v1/roles/{roleId}/permissions/bulk` — body `BulkPermissionsRequestDto`, returns `{ updatedRoleIds, auditId }`

### DTO Özet

- `PermissionAssignRequestDto` — userId, companyId?, projectId?, warehouseId?, roleId, assignedBy  
- `PermissionAssignmentUpdateRequestDto` — userId, companyId?, projectId?, warehouseId?, roleId, performedBy  
- `PermissionAssignmentDto` — assignmentId, roleId/name, scope (company/project/warehouse), permissions, auditId  
- `RoleDto` — id, name, description, memberCount, systemRole, policies[{moduleKey,moduleLabel,level,...}]  
- Zarf: `PagedResultDto<T>` — items, total, page, pageSize  
- Hata: `ErrorResponse` (STYLE-API-001) — error, message, fieldErrors?, meta.traceId

## Legacy (Deprecated, kaldırılacak)

- `/api/permissions/check|assign|assignments|assign/{id}`  
- `/api/access/roles`, `/api/access/roles/clone`, `/api/access/roles/bulk-permissions`

Bu uçlar geriye dönük uyumluluk için çalışmaya devam eder ancak yeni geliştirmelerde kullanılmamalıdır.
