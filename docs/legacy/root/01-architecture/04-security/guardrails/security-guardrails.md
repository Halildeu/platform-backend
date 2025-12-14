# Security Guardrails Pipeline

`.github/workflows/security-guardrails.yml` workflow'u güvenlik taramalarını otomatikleştirir; Node tabanlı theme/permission guardrail’lerinden sonra backend SAST/DAST + supply-chain adımları koşar. Her adım raporunu `test-results/security/**` altına bırakır ve pipeline sonunda artefact olarak upload eder.

## Gerekli GitHub Secrets
| Secret | Açıklama | Gereklilik |
|--------|----------|------------|
| `NVD_API_KEY` | Dependency-Check taraması için NVD API anahtarı. | Zorunlu – throttling’i aşmak için |
| `ZAP_TARGET_URL` | OWASP ZAP’ın tarayacağı preview ortamı (`https://preview.platform-security.internal`). | Opsiyonel – boşsa `DAST_FALLBACK_URL` repo var’ı kullanılır |
| `COSIGN_PRIVATE_KEY` | Cosign’ın `env://COSIGN_PRIVATE_KEY` şeklinde okuyacağı base64 key içeriği. | SBOM imzası için gerekli |
| `COSIGN_PASSWORD` | Cosign private key parolası (varsa). | Opsiyonel |
| `CSP_REPORT_ENDPOINT` | CSP raporlarının toplandığı endpoint (`https://security-gateway.internal/api/csp/report`). | CSP policy ile birebir eşleşmeli |

Secrets eklemek için GitHub → **Settings → Secrets and variables → Actions** menüsünü kullanabilirsiniz.

## Workflow Davranışı
- Node guardrail komutları (`tokens:build`, lint seti, permission registry testi, Keycloak CLI tests) kırmızıya düşerse pipeline erken biter.
- `scripts/ci/security/run-sast.sh` SpotBugs’ı `Max` effort/`Low` threshold ile çalıştırır; raporlar `test-results/security/spotbugs/` altına yazılır.
- `scripts/ci/security/run-dependency-scan.sh` OWASP Dependency-Check’in aggregate hedefini çağırır ve CVSS ≥7 bulunduğunda build’i kırar.
- Lokal kullanım: `./scripts/ci/security/setup-nvd-api-key.sh <key>` komutu ile shell’e NVD anahtarını export edip ardından `run-dependency-scan.sh` çalıştırabilirsiniz.
- `scripts/ci/security/run-dast.sh` varsayılan olarak `ZAP_TARGET_URL` secret’ını tarar; secret yoksa `DAST_FALLBACK_URL` repo var’ı (varsayılan `https://example.com`) kullanılarak baseline taraması çalışır. Raporlar (`xml/json/html`) `test-results/security/zap/` altındadır.
- `scripts/ci/security/generate-sbom-and-sign.sh` CycloneDX aggregate SBOM üretir (`test-results/security/sbom/bom.json`). `COSIGN_PRIVATE_KEY` set edildiğinde cosign SBOM’u imzalar ve `.sig` + `.cert` üretir.
- Workflow sonunda `test-results/security/**` klasörü `security-reports` adıyla artefact olarak yüklendiği için raporlar Actions sekmesinden indirilebilir.
- `security/sri-manifest.json` dosyası release sonrasında `npm run security:sri:rotate` ile güncellenmelidir; pipeline `npm run security:sri:check` çıktısında mismatch yakalarsa fail eder.
- CSP raporları `security/csp/reports/` dizininde JSON/NDJSON formatında toplanır. `npm run security:csp:summary` komutu policy’de tanımlı endpoint’i (`https://security-gateway.internal/api/csp/report`) referans alır ve rapor üretir; secret ile policy değerleri uyuşmazsa uyarı verir.

## Notlar
- Preview URL’si Kubernetes ingress üzerinden sağlanır; temel kimlik doğrulaması veya IP kısıtlaması varsa ZAP container’a uygun izin verildiğinden emin olun.
- `CSP_REPORT_ENDPOINT` secret’ı opsiyoneldir ancak policy değişiklikleriyle senkron kalması gerekir. Script, secret boşsa policy değerini kullanır fakat CI çıktısında bilgilendirici log üretir.

## Manuel Script Komutları
```bash
# SAST
./scripts/ci/security/run-sast.sh

# Bağımlılık taraması
./scripts/ci/security/run-dependency-scan.sh

# DAST (preview URL'i ile)
ZAP_TARGET_URL=https://preview.platform-security.internal ./scripts/ci/security/run-dast.sh

# SBOM üretimi + imza
COSIGN_PRIVATE_KEY="$(cat cosign.key)" COSIGN_PASSWORD=*** ./scripts/ci/security/generate-sbom-and-sign.sh

# SRI doğrulaması
npm run security:sri:check

# SRI hash rotasyonu (release sonrası)
npm run security:sri:rotate

# CSP rapor özetini oluşturmak
npm run security:csp:summary
```

Bu doküman, guardrail pipeline’ının işletilmesi ve gerekli gizli değişkenlerin yönetimi için referans olarak kullanılmalıdır.
