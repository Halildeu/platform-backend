package com.example.report.workcube;

import static com.example.report.workcube.ProjectActualProviderDtos.ProjectActualPage;
import static com.example.report.workcube.ProjectActualProviderDtos.ProjectActualRow;
import static com.example.report.workcube.ProjectActualProviderDtos.ProjectSourceLinePage;
import static com.example.report.workcube.ProjectActualProviderDtos.ProjectSourceLineRow;

import com.example.commonauth.scope.ScopeContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectActualProviderService {
    private static final int MAX_LIMIT = 2000;
    private static final int MAX_WINDOW_YEARS = 10;

    private final ProjectOptionsService projectOptions;
    private final ProjectActualProviderRepository repository;

    public ProjectActualProviderService(
            ProjectOptionsService projectOptions,
            ProjectActualProviderRepository repository) {
        this.projectOptions = projectOptions;
        this.repository = repository;
    }

    public ProjectActualPage findAuthorized(
            ScopeContext scope,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursorText,
            int requestedLimit) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid actuals date window");
        }
        if (from.plusYears(MAX_WINDOW_YEARS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actuals window exceeds ten years");
        }
        requireProjectAccess(scope, companyId, projectId);

        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        ProjectActualProviderRepository.Cursor cursor = decodeCursor(cursorText);
        List<ProjectActualRow> candidates =
                repository.find(companyId, projectId, from, to, cursor, limit);
        boolean hasMore = candidates.size() > limit;
        List<ProjectActualRow> page = new ArrayList<>(
                candidates.subList(0, Math.min(candidates.size(), limit)));
        List<ProjectActualRow> hashed = page.stream().map(this::withHash).toList();
        String nextCursor = hasMore && !hashed.isEmpty()
                ? encodeCursor(hashed.getLast())
                : null;
        return new ProjectActualPage(hashed, nextCursor, hasMore);
    }

    public ProjectSourceLinePage findAuthorizedSourceLines(
            ScopeContext scope,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursorText,
            int requestedLimit) {
        validateWindow(from, to);
        requireProjectAccess(scope, companyId, projectId);

        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        ProjectActualProviderRepository.SourceLineCursor cursor =
                decodeSourceLineCursor(cursorText);
        List<ProjectSourceLineRow> candidates =
                repository.findSourceLines(companyId, projectId, from, to, cursor, limit);
        boolean hasMore = candidates.size() > limit;
        List<ProjectSourceLineRow> page = new ArrayList<>(
                candidates.subList(0, Math.min(candidates.size(), limit)));
        List<ProjectSourceLineRow> hashed =
                page.stream().map(this::withSourceLineHash).toList();
        String nextCursor = hasMore && !hashed.isEmpty()
                ? encodeSourceLineCursor(hashed.getLast())
                : null;
        return new ProjectSourceLinePage(hashed, nextCursor, hasMore);
    }

    private ProjectActualRow withHash(ProjectActualRow row) {
        String canonical = String.join("|",
                row.sourceSystem(),
                Integer.toString(row.sourceLedgerYear()),
                Long.toString(row.sourceCompanyId()),
                Long.toString(row.sourceProjectId()),
                Long.toString(row.journalCardId()),
                Long.toString(row.journalRowId()),
                String.valueOf(row.postingDate()),
                String.valueOf(row.accountCode()),
                row.debitCredit(),
                row.signedAmount().toPlainString(),
                row.currency(),
                String.valueOf(row.actionType()),
                String.valueOf(row.actionId()),
                String.valueOf(row.actionRowId()),
                row.documentType(),
                String.valueOf(row.documentNo()),
                row.resolutionStatus(),
                Boolean.toString(row.cancelled()));
        return new ProjectActualRow(
                row.sourceSystem(),
                row.sourceLedgerYear(),
                row.sourceCompanyId(),
                row.sourceProjectId(),
                row.journalCardId(),
                row.journalRowId(),
                row.postingDate(),
                row.accountCode(),
                row.debitCredit(),
                row.signedAmount(),
                row.currency(),
                row.actionType(),
                row.actionId(),
                row.actionRowId(),
                row.documentType(),
                row.documentNo(),
                row.resolutionStatus(),
                row.cancelled(),
                sha256(canonical));
    }

    private ProjectSourceLineRow withSourceLineHash(ProjectSourceLineRow row) {
        String canonical = String.join("|",
                row.sourceSystem(),
                Integer.toString(row.sourceLedgerYear()),
                Long.toString(row.sourceCompanyId()),
                Long.toString(row.sourceProjectId()),
                Long.toString(row.sourceDocumentId()),
                Long.toString(row.sourceLineId()),
                Integer.toString(row.lineOrdinal()),
                String.valueOf(row.documentDate()),
                row.documentType(),
                row.documentKind(),
                String.valueOf(row.documentNo()),
                String.valueOf(row.productName()),
                String.valueOf(row.description()),
                String.valueOf(row.quantity()),
                String.valueOf(row.unit()),
                String.valueOf(row.unitPrice()),
                row.netAmount().toPlainString(),
                String.valueOf(row.taxRate()),
                row.taxAmount().toPlainString(),
                row.grossAmount().toPlainString(),
                row.currency(),
                String.valueOf(row.accountCode()),
                Boolean.toString(row.cancelled()));
        return new ProjectSourceLineRow(
                row.sourceSystem(),
                row.sourceLedgerYear(),
                row.sourceCompanyId(),
                row.sourceProjectId(),
                row.sourceDocumentId(),
                row.sourceLineId(),
                row.lineOrdinal(),
                row.documentDate(),
                row.documentType(),
                row.documentKind(),
                row.documentNo(),
                row.productName(),
                row.description(),
                row.quantity(),
                row.unit(),
                row.unitPrice(),
                row.netAmount(),
                row.taxRate(),
                row.taxAmount(),
                row.grossAmount(),
                row.currency(),
                row.accountCode(),
                row.cancelled(),
                sha256(canonical));
    }

    private String encodeCursor(ProjectActualRow row) {
        String value = row.sourceLedgerYear() + "|" + row.journalRowId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeSourceLineCursor(ProjectSourceLineRow row) {
        String value = row.sourceLedgerYear() + "|" + row.sourceLineId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private ProjectActualProviderRepository.Cursor decodeCursor(String cursorText) {
        if (cursorText == null || cursorText.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursorText),
                    StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            int ledgerYear = Integer.parseInt(decoded.substring(0, separator));
            long rowId = Long.parseLong(decoded.substring(separator + 1));
            if (ledgerYear < 2000 || ledgerYear > 2200 || rowId < 0) {
                throw new IllegalArgumentException("unsafe cursor");
            }
            return new ProjectActualProviderRepository.Cursor(ledgerYear, rowId);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid actuals cursor", invalid);
        }
    }

    private ProjectActualProviderRepository.SourceLineCursor decodeSourceLineCursor(
            String cursorText) {
        if (cursorText == null || cursorText.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursorText),
                    StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            int ledgerYear = Integer.parseInt(decoded.substring(0, separator));
            long sourceLineId = Long.parseLong(decoded.substring(separator + 1));
            if (ledgerYear < 2000 || ledgerYear > 2200 || sourceLineId < 0) {
                throw new IllegalArgumentException("unsafe cursor");
            }
            return new ProjectActualProviderRepository.SourceLineCursor(
                    ledgerYear, sourceLineId);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid source-line cursor", invalid);
        }
    }

    private void validateWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid actuals date window");
        }
        if (from.plusYears(MAX_WINDOW_YEARS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actuals window exceeds ten years");
        }
    }

    private void requireProjectAccess(
            ScopeContext scope,
            long companyId,
            long projectId) {
        boolean authorized = projectOptions.findAuthorized(scope, companyId).stream()
                .anyMatch(project -> project.id() == projectId);
        if (!authorized) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Project is outside the caller scope");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
