## PR‑1: AuditID — Mutasyon Yanıtlarında `auditId`

Özet
- permission-service: assign/update yanıtlarına `auditId`, revoke için `{ status: "ok", auditId }`.
- user-service: `PUT /api/users/{id}` yanıtına `auditId`.
- Dokümanlar Doc Policy’ye uygun güncellendi.

Değişiklikler
- permission-service
  - permission-service/src/main/java/com/example/permission/dto/PermissionResponse.java — `auditId` alanı eklendi.
  - permission-service/src/main/java/com/example/permission/service/PermissionService.java — audit event kaydı sonrası yanıt `auditId` set edilir; revoke `auditId` döndürür.
  - permission-service/src/main/java/com/example/permission/dto/MutationAckResponse.java — revoke yanıt DTO’su.
  - permission-service/src/main/java/com/example/permission/controller/PermissionController.java — DELETE `/assign/{assignmentId}` `200 OK` + body döner.
- user-service
  - user-service/src/main/java/com/example/user/dto/UserResponse.java — `auditId` alanı eklendi.
  - user-service/src/main/java/com/example/user/model/UserAuditEvent.java — basit audit entity.
  - user-service/src/main/java/com/example/user/repository/UserAuditEventRepository.java — repository.
  - user-service/src/main/java/com/example/user/service/UserAuditEventService.java — kayıt servisi.
  - user-service/src/main/java/com/example/user/controller/UserController.java — `PUT /{userId}` audit kaydı + `auditId` set.
  - user-service/src/main/resources/db/migration/V3__create_user_audit_events.sql — Flyway migration.
- dokümanlar
  - docs/backend/permission-service.md — DoD: `auditId ✓`, revoke yanıtı örneği.
  - docs/backend/user-service.md — Update yanıtında `auditId`.
  - docs/api/users.api.md — Update endpoint yanıt örneği `auditId` içerir.

Kabul Kriterleri
- Mutasyon yanıtında `auditId` var; permission audit feed’de `/api/audit/events?id=<auditId>` ile görüntülenebiliyor.
- Revoke `200 OK` + `{ status, auditId }` döner.
- User update yanıtında `auditId` var; Flyway migration başarıyla uygulanır.

Doc Policy Checklist
- [x] API değişikliği docs/api/*.md güncellendi
- [x] BACKEND DoD/RAG güncellendi (docs/backend/*.md)
- [x] Güvenlik davranışı değişmedi (rate-limit PR‑3’te ele alınacak)

Notlar
- Yanıtlara opsiyonel `X-Audit-Id` header eklemek istenirse küçük bir ek PR ile eklenebilir.

