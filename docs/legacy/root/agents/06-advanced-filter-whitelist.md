# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi advanced filter whitelist (API güvenliği) kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

## Advanced Filter Whitelist (API Güvenliği)

Amaç
- AG Grid advanced filter modelinin backend’de güvenli parse edilmesi ve yalnız izinli alan/operatörlerle çalışması.

Örnek Whitelist (Users)
- Alanlar: `fullName` (string), `email` (string), `status` (enum), `role` (string), `lastLoginAt` (date)
- Operatörler: `equals`, `notEqual`, `contains`, `notContains`, `lessThan`, `greaterThan`, `inRange`

Negatif Testler
- Bilinmeyen alan/operatör → 400
- Tip uyuşmazlığı (date alanına string contains vb.) → 400
- Aşırı derin JSON → 400

Kabul Kriterleri
- Whitelist uygulanır; parse edilemeyen/izinli olmayan sorgular reddedilir
- Parametre bağlama (prepared statements) ile injection güvenliği sağlanır

Bağlantılar
- `docs/03-delivery/api/users.api.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`
