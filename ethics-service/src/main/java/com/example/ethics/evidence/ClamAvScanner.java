package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bounded ClamAV INSTREAM client. The scanner response is classified but never
 * logged with attachment identifiers or content.
 */
@Component
@ConditionalOnProperty(
        name = "ethics.evidence.processor.mode",
        havingValue = "clamav-reference")
public class ClamAvScanner {
    private static final int CHUNK = 64 * 1024;
    private final EvidenceProperties properties;

    public ClamAvScanner(EvidenceProperties properties) {
        this.properties = properties;
    }

    public ScanResult scan(byte[] content) {
        EvidenceProperties.Processor config = properties.getProcessor();
        int timeoutMillis = Math.toIntExact(config.getTimeout().toMillis());
        String observedVersion = queryVersion(timeoutMillis);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.getClamavHost(), config.getClamavPort()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += CHUNK) {
                int length = Math.min(CHUNK, content.length - offset);
                output.writeInt(length);
                output.write(content, offset, length);
            }
            output.writeInt(0);
            output.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) >= 0 && value != 0) {
                if (response.size() >= 4096) {
                    throw new IllegalStateException("ClamAV response exceeded bound");
                }
                response.write(value);
            }
            String result = response.toString(StandardCharsets.US_ASCII);
            if (result.endsWith(" OK")) return new ScanResult(Verdict.CLEAN, observedVersion);
            if (result.contains(" FOUND")) return new ScanResult(Verdict.MALICIOUS, observedVersion);
            return new ScanResult(Verdict.UNKNOWN, observedVersion);
        } catch (Exception error) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_SCANNER_UNAVAILABLE",
                    error);
        }
    }

    private String queryVersion(int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(
                    properties.getProcessor().getClamavHost(),
                    properties.getProcessor().getClamavPort()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) >= 0 && value != 0) {
                if (response.size() >= 4096) {
                    throw new IllegalStateException("ClamAV version response exceeded bound");
                }
                response.write(value);
            }
            String version = response.toString(StandardCharsets.US_ASCII).trim();
            if (version.isBlank() || version.length() > 120
                    || !version.matches("[A-Za-z0-9./:_ +()-]+")) {
                throw new IllegalStateException("ClamAV version response was invalid");
            }
            return version;
        } catch (Exception error) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_SCANNER_VERSION_UNAVAILABLE",
                    error);
        }
    }

    public enum Verdict { CLEAN, MALICIOUS, UNKNOWN }
    public record ScanResult(Verdict verdict, String rulesVersion) {}
}
