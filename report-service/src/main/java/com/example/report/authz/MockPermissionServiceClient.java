package com.example.report.authz;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Conntest-only mock — returns a super-admin response so reports can be
 * tested without Keycloak / permission-service.
 */
@Component
@Profile("conntest")
public class MockPermissionServiceClient implements PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(MockPermissionServiceClient.class);

    @Override
    public AuthzMeResponse getAuthzMe(Jwt jwt) {
        log.info("[conntest] Returning mock super-admin AuthzMeResponse");
        AuthzMeResponse response = new AuthzMeResponse();
        response.setUserId("conntest-user");
        response.setSuperAdmin(true);
        response.setPermissions(List.of(
                "REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE",
                // Sales
                "reports.satis-ozet.view", "reports.satis-ozet.financials", "reports.satis-ozet.all-stores",
                "reports.stok-durum.view", "reports.stok-durum.costs", "reports.stok-durum.all-warehouses",
                // HR reports
                "reports.hr-personel-listesi.view", "reports.hr.all-companies",
                "reports.hr-maas-raporu.view", "reports.hr.salary-view",
                "reports.hr-giris-cikis.view", "reports.hr-puantaj.view",
                "reports.hr-maas-gecmisi.view", "reports.hr-egitim-katilim.view",
                "reports.hr-izin-raporu.view", "reports.hr-bordro-detay.view",
                // HR dashboards
                "dashboards.hr-analytics.view", "dashboards.hr-finansal.view",
                // Finance reports
                "reports.fin-banka-hareketleri.view", "reports.fin-kasa-hareketleri.view",
                "reports.fin-faturalar.view", "reports.fin-faturalar.financials",
                "reports.fin-cek-senet.view", "reports.fin-cari-hareketler.view",
                "reports.fin.all-companies",
                // Finance dashboard
                "dashboards.fin-analytics.view"
        ));
        response.setAllowedScopes(List.of());
        return response;
    }
}
