# Contributing to platform-backend

**Authority**: Bu repo platform-backend Java mikroservis + Zanzibar plane kaynağı.
**ADR**: [ADR-0004 split-repo authority transfer](https://github.com/Halildeu/platform-k8s-gitops/blob/main/docs/adr/0004-split-repo-authority-transfer.md)
**Canonical manifest repo**: [platform-k8s-gitops](https://github.com/Halildeu/platform-k8s-gitops)

---

## Repo sınırı

- **platform-backend** (bu): 9 Java mikroservis + Zanzibar plane (permission-service + common-auth/openfga + openfga-runtime) + discovery-server legacy + Flyway migrations + Dockerfile'lar
- **platform-k8s-gitops** (canonical): Kustomize + Helm + ArgoCD + day-2 ops + PLAN + ADR + current-state + host-compose
- **platform-web** (kardeş): MFE shell + mfe-* + design-system + i18n-dicts
- **platform-ssot** (deprecated, Faz 19.10'da archive): Faz 19 migration kaynağı

## Geliştirme döngüsü

### Lokal

```bash
./mvnw -q -DskipTests test-compile   # tüm modüller compile
./mvnw -q -DskipTests -pl <service> -am verify  # tek servis
```

Java 21 Temurin gerekli. `./mvnw` zaten repo'da (Maven Wrapper).

### PR açma

1. Branch: `feat/<short-desc>` veya `fix/<short-desc>`
2. Commit pattern: `<type>(<scope>): <summary>` (feat/fix/refactor/docs/chore/test)
3. PR template otomatik dolduruluyor (`.github/PULL_REQUEST_TEMPLATE.md`)
4. `ci-mvn-check` PASS olmadan merge yok (branch protection)

### Dual-build dönem (Faz 19.8'e kadar)

Platform-ssot CI aynı image'ları `platform-ssot/*` adıyla build ediyor (paralel). platform-backend CI aynı GHCR image adına push edecek (minimum hareket, Codex önerisi). Faz 19.9 cutover'da gitops digest pin değişir.

## Image registry

- GHCR: `ghcr.io/halildeu/platform-ssot-<service>:<tag>` (isimlendirme korundu, değişiklik Faz 19.8+'da)
- Tag: `sha-<short>` immutable (D30, ADR-0002)
- Moving tag (`main-stable`) YASAK

## Zanzibar plane koruma

permission-service + common-auth/openfga + openfga-runtime:
- Store-id + model-id Vault `kv/platform/openfga` authoritative (platform-k8s-gitops `kustomize/base/apps/permission-service/ops/externalsecret.yaml`)
- OpenFGA DSL değişikliği = model version bump + test fixture'larla doğrulama
- Scoped allow seed fixtures platform-k8s-gitops `docs/prod-scoped-allow-seed-runbook.md`'de kalır

## Branch protection

- `main` branch protected
- PR required (admin bypass OK, tek geliştirici deadlock önlemek için)
- `ci-mvn-check` required status check
- Direct push blocked

## Kaynak migration geçmişi

Bu repo `platform-ssot` `backend/` subdirectory'sinden `git filter-repo` ile migrate edildi (Faz 19.1, 2026-04-24):
- 2,696 ssot commit → 338 filtered commit
- sha-map: [platform-k8s-gitops/docs/faz-19-evidence/sha-map-platform-backend.txt](https://github.com/Halildeu/platform-k8s-gitops/blob/main/docs/faz-19-evidence/sha-map-platform-backend.txt)

## Referans

- ADR-0004 split-repo authority transfer
- platform-k8s-gitops PLAN.md §Faz 19
- Codex thread 019dc0ac (detailed 10-step AGREE)
