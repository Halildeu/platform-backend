package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * ES-104K dilim 2 (#2930) — the digest-pinned converter over its one endpoint.
 *
 * <p>Bounded on every axis: connect+request timeouts from configuration, and the response
 * is refused past four times the attachment contract maximum — a converter that inflates
 * a photo beyond that is answering a question nobody asked. Failures map to UNAVAILABLE
 * (retry via the pipeline's lease machinery), except an oversized answer, which is POLICY:
 * retrying it would produce the same oversized answer.
 */
@Component
@ConditionalOnExpression(
        "!'${ethics.evidence.processor.heic-converter-url:}'.isEmpty()")
public class HttpHeicConverter implements HeicConverter {

    private final EvidenceProperties properties;
    private final HttpClient client;

    public HttpHeicConverter(EvidenceProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.getProcessor().getTimeout())
                .build();
    }

    @Override
    public byte[] toPng(byte[] heic) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getProcessor().getHeicConverterUrl()
                            + "/convert?type=png"))
                    .timeout(properties.getProcessor().getTimeout())
                    .header("Content-Type", "image/heic")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(heic))
                    .build();
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.UNAVAILABLE,
                        "EVIDENCE_HEIC_CONVERTER_ERROR");
            }
            long ceiling = properties.getMaxBytes() * 4;
            if (response.body().length == 0 || response.body().length > ceiling) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.POLICY,
                        "EVIDENCE_HEIC_CONVERTER_OUTPUT_LIMIT");
            }
            return response.body();
        } catch (IOException error) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_HEIC_CONVERTER_UNAVAILABLE",
                    error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_HEIC_CONVERTER_UNAVAILABLE",
                    error);
        }
    }
}
