## Özet

<!-- Bu PR ne yapıyor? 1-2 cümle. -->

## Scope

- [ ] Service(s): auth / user / variant / core-data / report / schema / api-gateway / discovery-server
- [ ] Zanzibar plane: permission-service / common-auth/openfga / openfga-runtime
- [ ] Infrastructure: pom.xml / Dockerfile / CI workflow
- [ ] Docs: README / CONTRIBUTING / ADR reference

## Test

- [ ] `./mvnw -q -DskipTests test-compile` PASS
- [ ] `./mvnw -q -DskipTests -pl <service> -am verify` PASS
- [ ] CI `ci-mvn-check` GREEN

## Dual-build etkisi (Faz 19.8'e kadar)

- [ ] platform-ssot'ta denk/paralel değişiklik var mı?
- [ ] Image ref değişikliği platform-k8s-gitops digest pin bozmaz

## Zanzibar koruma (permission-service / common-auth/openfga için)

- [ ] OpenFGA model/store-id değişmedi, veya değiştiyse fixture doğrulandı
- [ ] Scoped allow seed fixtures compat

## Referans

- ADR-0004: split-repo authority transfer
- Related PR: <!-- platform-k8s-gitops veya platform-web -->
