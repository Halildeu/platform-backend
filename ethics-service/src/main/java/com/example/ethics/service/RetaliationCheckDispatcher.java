package com.example.ethics.service;

import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.RetaliationCheckRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ES-213 (#3375) — puts the retaliation question to the reporter, through the mailbox they
 * already hold.
 *
 * <p>Without this the schedule is a list of dates somebody has to remember to act on, and a
 * protection duty discharged from memory is discharged for the cases people feel bad about
 * and forgotten for the rest. Directive 2019/1937 art. 21 owes the reporter protection, not
 * a calendar entry about protecting them.
 *
 * <p><strong>Works for anonymous reporters.</strong> The message goes to the case mailbox,
 * so being asked after never requires having given a name. A scheme that could only reach
 * people who identified themselves would reach exactly the ones who felt safe enough not to
 * need it.
 */
@Component
public class RetaliationCheckDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RetaliationCheckDispatcher.class);

    /**
     * Deliberately plain, and deliberately not alarming.
     *
     * <p>It does not ask "has anything bad happened", which invites a reflexive no from
     * someone who has not connected a transfer to their report. It names the kinds of thing
     * that count — the same forms art. 19 lists — because a person rarely has the word for
     * what is happening to them, and giving the word is most of the help.
     *
     * <p>It also says why they are hearing from us. A message with no explanation, months
     * after a case closed, reads as the case being reopened.
     */
    static final String TEMPLATE = """
            Merhaba,

            %d ay önce kapanan bildiriminizle ilgili olarak durumunuzu soruyoruz. Bu rutin bir \
            kontroldür; dosyanız yeniden açılmadı ve sizden bir işlem beklenmiyor.

            Bildirimde bulunduğunuz için size karşı olumsuz bir işlem yapılması yasaktır. Buna \
            örnek olarak şunlar sayılabilir: işten çıkarma veya uzaklaştırma, görev ya da yer \
            değişikliği, ücret indirimi, terfi engelleme, olumsuz performans değerlendirmesi, \
            disiplin işlemi, eğitimden mahrum bırakma, dışlanma veya yıldırma, sözleşmenin \
            yenilenmemesi.

            Bunlardan biri yaşandıysa ya da emin değilseniz bu mesaja yanıt yazmanız yeterli. \
            Kimliğinizi paylaşmanız gerekmiyor.

            Bir şey yaşanmadıysa yanıt vermenize gerek yok.
            """;

    private final RetaliationCheckRepository checks;
    private final EthicsService ethics;

    public RetaliationCheckDispatcher(RetaliationCheckRepository checks, EthicsService ethics) {
        this.checks = checks;
        this.ethics = ethics;
    }

    @Scheduled(fixedDelayString = "${ethics.retaliation.dispatch-delay:1h}")
    public void dispatchDue() {
        dispatchDueAt(Instant.now());
    }

    /**
     * One message per due, unasked check.
     *
     * <p>Each is its own transaction and its own failure. A case whose mailbox write fails —
     * a deleted case, a storage fault — must not stop the other reporters from being asked,
     * which is what a single batch transaction would do. The check simply stays unasked and
     * shows up in the overdue count, which is the honest outcome.
     */
    void dispatchDueAt(Instant now) {
        List<RetaliationCheck> due = checks.findAll().stream()
                .filter(c -> c.getClosedAt() == null && c.getAskedAt() == null && !c.getDueAt().isAfter(now))
                .toList();
        for (RetaliationCheck check : due) {
            try {
                dispatchOne(check, now);
            } catch (RuntimeException e) {
                // No case id in the log line. A whistleblowing case identifier in an
                // operational log is a correlation handle for anyone who can read logs, and
                // the count is what an operator needs anyway.
                log.warn("retaliation check dispatch failed; it stays overdue", e);
            }
        }
    }

    @Transactional
    void dispatchOne(RetaliationCheck check, Instant now) {
        // Keyed on the check, so a retry after a partial failure cannot put the same
        // question to the same person twice — which would read, to them, as the
        // organisation having lost track of their case.
        ethics.systemReply(check.getOrgId(), check.getCaseId(),
                "retaliation-check-" + check.getId(),
                TEMPLATE.formatted(check.getPeriodMonths()),
                // Not an acknowledgement of the original report. Checks exist only for
                // closed cases and closure already required a reporter-visible message, so
                // acknowledgedAt is set long before this runs; the no-op says that is
                // intended rather than overlooked.
                () -> { });
        check.markAsked(now);
        checks.save(check);
    }
}
