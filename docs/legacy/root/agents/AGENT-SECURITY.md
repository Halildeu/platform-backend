# AGENT-SECURITY

## Amaç (Purpose)
- Backend sistemindeki tüm güvenlik ve compliance guardrail’lerini tek bir ajan bakış açısından yönetmek.  
- LLM agent güvenlik konulu işlerde (SPEC, STORY, RUNBOOK, PIPELINE tasarımı) hangi kurallara bakacağını ve nasıl davranacağını tanımlar.

> Not: Bu agent dokümanı `docs/agents/MP-AGENT-FORMAT.md` kurallarına göre tasarlanmıştır.  
> Yeni security agent’ı veya alt agent dokümanları eklerken aynı rehbere uyulmalıdır.

## Kapsam (Scope)

### Kapsam içi
- `docs/agents/*.md` altında yer alan güvenlik/compliance kural dokümanları:
  - `01-security-architecture.md`
  - `02-secret-standards.md`
  - `03-secret-rotation-pipeline.md`
  - `04-security-testing.md`
  - `05-export-security.md`
  - `06-advanced-filter-whitelist.md`
  - `07-opa-policy-gateway.md`
  - `08-jwt-rotation-plan.md`
  - `09-keycloak-access-request.md`
  - `10-keycloak-access-request-cli.md`
  - `security-guardrails-automation.md`
- Bu kuralların uygulanacağı doküman türleri:
  - SPEC’ler: `docs/05-governance/06-specs/*.md`
  - Story’ler: `docs/05-governance/02-stories/*.md`
  - Runbook’lar: `docs/04-operations/**/*.md`

### Kapsam dışı
- İş/ürün önceliklendirme (Epic/Story roadmap) kararları (`docs/05-governance/PROJECT_FLOW.md` ve ilgili Epic/Story dokümanları).
- Yüksek seviye sistem mimarisi (`docs/01-architecture/**`), bu ajan yalnız mevcut mimari kararlarla uyumu gözetir.

## Zorunlu Referanslar (Required Docs)
- Güvenlik mimarisi: `docs/agents/01-security-architecture.md`
- Secret standardı: `docs/agents/02-secret-standards.md`
- Secret rotasyon pipeline’ı: `docs/agents/03-secret-rotation-pipeline.md`
- Güvenlik test stratejisi: `docs/agents/04-security-testing.md`
- Export güvenliği: `docs/agents/05-export-security.md`
- Advanced filter whitelist: `docs/agents/06-advanced-filter-whitelist.md`
- OPA policy gateway: `docs/agents/07-opa-policy-gateway.md`
- JWT rotasyon planı: `docs/agents/08-jwt-rotation-plan.md`
- Keycloak access request süreci: `docs/agents/09-keycloak-access-request.md`
- Keycloak access request CLI: `docs/agents/10-keycloak-access-request-cli.md`
- Security guardrails otomasyonu: `docs/agents/security-guardrails-automation.md`

## Çalışma Kuralları (How the Agent Should Behave)

- Normatif kurallar:
  - Yukarıdaki `docs/agents/*.md` dosyaları **zorunlu kural setleri**dir; LLM agent bunları “MUST” olarak kabul eder.
  - Yeni SPEC/STORY/RUNBOOK yazarken bu dosyalarda tanımlı kuralları **yeniden tanımlamaz**, yalnız uygular veya ilgili bölüme referans verir.
  - Bir kural değişecekse, değişiklik ilgili `docs/agents/*.md` dosyasında yapılır; başka dokümanlarda override edilmez.

- Uygulama biçimi:
  - SPEC yazarken:
    - Güvenlik/validation/NFR kısımlarında uygun `docs/agents/*` maddelerine açık referans eklenir (ör. “bkz. `docs/agents/06-advanced-filter-whitelist.md`”).  
  - Story yazarken:
    - DoD veya Acceptance bölümünde, hangi güvenlik guardrail’lerinin geçerli olduğu belirtilir (örn. export güvenliği, advancedFilter whitelist, JWT rotasyonu).
  - Runbook yazarken:
    - Operasyon adımlarının ilgili güvenlik standardına uygunluğu sağlanır ve ilgili `docs/agents/*` dokümanı linklenir.

- Çatışma durumları:
  - Farklı dokümanlar arasında çelişki görünürse, AGENT-SECURITY ajanı şu öncelik sırasını uygular:
    1. Yayınlanmış security ADR’leri (`docs/05-governance/05-adr/*.md`)
    2. `docs/agents/*.md` içindeki normatif kurallar
    3. SPEC/Story/Runbook içindeki yorumlayıcı metinler
  - Çelişki tespiti halinde, ilgili `docs/agents/*` dosyasının güncellenmesi için not düşülür; kural asla rastgele “esnetilmez”.
