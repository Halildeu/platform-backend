## OTEL JS Kurulumu ve Exporter Ayarları

Amaç
- Frontend uygulamalarında OpenTelemetry JS SDK ile izleme/telemetri toplayıp OTLP exporter üzerinden Tempo/Loki gibi sistemlere göndermek.

Kurulum (özet)
1) Bağımlılıklar (örnek):
```
npm i @opentelemetry/api @opentelemetry/sdk-trace-web @opentelemetry/exporter-trace-otlp-http @opentelemetry/instrumentation-document-load @opentelemetry/instrumentation-fetch @opentelemetry/instrumentation-xml-http-request
```
2) Başlatma (örnek kod parçası):
```ts
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';

const provider = new WebTracerProvider();
const exporter = new OTLPTraceExporter({ url: process.env.OTLP_URL });
provider.addSpanProcessor(new BatchSpanProcessor(exporter));
provider.register();

registerInstrumentations({
  instrumentations: [
    new DocumentLoadInstrumentation(),
    new FetchInstrumentation(),
    new XMLHttpRequestInstrumentation(),
  ],
});
```

Konfig
- OTLP_URL: `https://otel-collector.example.com/v1/traces`
- Service name: `resource: { service.name: 'mfe-shell' }` (çatı), remotelere uygun isim verin.

Kabul Kriterleri
- Page view ve kritik işlemler span olarak düşer; hata oranı (error rate) panoda görsel.
- QA ortamında exporter bağlantısı doğrulanır (Tempo’da trace alınır).

Bağlantılar
- `docs/04-operations/02-monitoring/tempo-loki-dashboards.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`
