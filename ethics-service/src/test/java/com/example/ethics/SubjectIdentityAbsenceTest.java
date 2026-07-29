package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.api.EthicsDtos;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-203/B+ — who the report is <em>about</em> must not travel on ordinary surfaces
 * (#946).
 *
 * <p>The subject of a report — the person being reported on — is a different fact from who
 * handles the case, and belongs in a narrower circle. Nobody has been proven to have done
 * anything; opening their identity to everyone who triages would be its own harm, separate
 * from and additional to the reporter's exposure.
 *
 * <p>That field does not exist yet, which is exactly why this test is written now. Locking
 * the invariant before the data exists means a careless addition fails on the day it is
 * written, in the pull request, rather than months later on a live surface where the leak
 * has already happened. The design (recorded on #946) requires the identity to reach a
 * caller only through a WORM-gated reveal endpoint holding its own grant — never as a field
 * on a case DTO, and not even as a "there is a subject" indicator.
 *
 * <h2>The naming hazard this test has to navigate</h2>
 *
 * <p>{@code CaseSummary} and {@code CaseDetail} already carry a component called
 * {@code subject}, and it means the report's <b>title</b> — not a person. One word, two
 * meanings, in a product where confusing them is the harm. The exemption below is therefore
 * exact and singular: the bare name {@code subject}. Anything that reads as identity —
 * {@code subjectRef}, {@code subjectId}, {@code subjectName}, {@code subjectBlobId} — is
 * refused, so the ambiguity cannot be used as cover.
 */
class SubjectIdentityAbsenceTest {

    /**
     * The report's title. Present since the beginning, means a headline, not a human.
     *
     * <p>Deliberately a single exact string rather than a prefix: {@code subject} is allowed,
     * {@code subjectAnything} is not.
     */
    private static final Set<String> TITLE_FIELD = Set.of("subject");

    /** Fragments that mark a component as carrying, or hinting at, subject identity. */
    private static final List<String> IDENTITY_MARKERS = List.of(
            "subjectref", "subjectid", "subjectname", "subjectemail", "subjectperson",
            "subjectidentity", "subjecthandle", "subjectblob", "subjectmatch", "subjectcipher",
            "accused", "reportedperson", "reportedabout");

    @Test
    @DisplayName("dışarı çıkan hiçbir DTO konu kimliği taşımaz")
    void noOutboundDtoCarriesSubjectIdentity() {
        var offenders = new ArrayList<String>();
        for (Class<?> dto : EthicsDtos.class.getDeclaredClasses()) {
            if (!dto.isRecord()) continue;
            for (RecordComponent component : dto.getRecordComponents()) {
                String name = component.getName();
                if (TITLE_FIELD.contains(name)) continue;
                String lowered = name.toLowerCase(Locale.ROOT);
                for (String marker : IDENTITY_MARKERS) {
                    if (lowered.contains(marker)) {
                        offenders.add(dto.getSimpleName() + "." + name);
                        break;
                    }
                }
            }
        }
        assertThat(offenders)
                .as("Konu kimliği yalnız WORM kayıtlı, ayrı yetkili ifşa ucundan çıkar — "
                        + "sıradan bir DTO alanı olarak değil (#946)")
                .isEmpty();
    }

    /**
     * The exemption must stay exact. If {@code subject} ever stops meaning "title" — or if
     * somebody widens the allowance to a prefix — this is where it should hurt.
     */
    @Test
    @DisplayName("muafiyet tam olarak 'subject' ile sınırlı")
    void theTitleExemptionIsExactAndNotAPrefix() {
        assertThat(TITLE_FIELD).containsExactly("subject");
        for (String marker : IDENTITY_MARKERS) {
            assertThat(TITLE_FIELD).doesNotContain(marker);
        }
    }

    /**
     * A guard that scans nothing passes forever. The suite has to actually reach the DTOs.
     */
    @Test
    @DisplayName("tarama gerçekten DTO'lara ulaşıyor")
    void theScanActuallySeesTheDtos() {
        long records = List.of(EthicsDtos.class.getDeclaredClasses()).stream()
                .filter(Class::isRecord).count();
        assertThat(records).as("EthicsDtos içindeki record sayısı").isGreaterThanOrEqualTo(10);

        boolean sawTitle = List.of(EthicsDtos.class.getDeclaredClasses()).stream()
                .filter(Class::isRecord)
                .flatMap(dto -> List.of(dto.getRecordComponents()).stream())
                .anyMatch(component -> component.getName().equals("subject"));
        assertThat(sawTitle).as("başlık alanı görülebiliyor — tarama boş kümede çalışmıyor").isTrue();
    }
}
