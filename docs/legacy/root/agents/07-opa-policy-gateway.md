# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi Gateway OPA policy-as-code kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# Gateway OPA Policy-As-Code Stratejisi

Faz 3 kapsamında API Gateway üzerinde claim tabanlı yetkilendirme için OPA (Open Policy Agent) entegrasyonu.

## 1. Mimari
- OPA sidecar veya embedded OPA bundle:  
  - Gateway (Spring Cloud Gateway / Kong / Envoy) → OPA (Rego policies).  
  - Policy repo ayrı Git repo (ör. `security/policy-gateway`).  
- Deployment: OPA bundle (tar.gz) registry → Gateway pod init container indirir.

## 2. Policy Yapısı
- Claim standardı: `svc`, `env`, `perm`, `sub`, `aud`.  
- Rego modülleri:
  - `authz/permissions.rego` – JWT claims → izin kararları.  
  - `authz/routes.rego` – path bazlı izin.  
  - `data/services.yaml` – servis/metot metadata (OPA data document).

### Örnek Policy
```rego
package authz

default allow = false

allow {
  input.claims.perm[_] == required_perm
  input.request.path == route.path
}

required_perm = route.permission

route := data.routes[input.request.path]
```

## 3. CI/CD Entegrasyonu
- Policy repo PR pipeline:  
  - `opa fmt` / `opa check` / `opa test`.  
  - Conftest (gateway config lint).  
- Bundle publish (S3/Artifactory) → version tag `vYYYYMMDD`.  
- Gateway deployment env var `OPA_BUNDLE_VERSION`.

## 4. Gateway Entegrasyonu
- OPA sidecar konfig:
  ```yaml
  containers:
    - name: opa
      image: openpolicyagent/opa:latest
      args: ["run","--server","--addr=:8181","--set=services.bundle.url=${OPA_BUNDLE_URL}"]
    - name: gateway
      env:
        - name: OPA_URL
          value: http://127.0.0.1:8181/v1/data/authz/allow
  ```
- Gateway filter JWT doğrulama sonrası OPA’ya payload gönderir.

## 5. Rollout Planı
1. Policy repo oluştur, default deny → audit mode.  
2. Staging’de OPA sidecar + audit policy (gözetim).  
3. Audit loglara göre policy refine.  
4. `enforce` modu aç, fail-fast.  
5. Prod rollout (canary).

## 6. Monitoring
- OPA decision logs → Loki/ELK.  
- Metrics: `opa_eval_duration_seconds`, `opa_decision_total`.  
- Alert: `opa_decision_denied_total` spike.

## 7. Checklist
- [ ] Policy repo ve pipeline (opa fmt/check/test) aktif.  
- [ ] Gateway OPA sidecar audit mode’da çalışıyor.  
- [ ] Enforce moduna geçiş planlandı ve test edildi.  
- [ ] Metrics/log dashboard güncellendi.  
- [ ] Dokümantasyon (Confluence) linklendi.

---
**Sonraki adım:** Vault Transit Engine entegrasyonu.
