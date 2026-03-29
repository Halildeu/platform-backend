package com.example.commonauth.scope;

/**
 * Constants for Hibernate @Filter configuration.
 * Each entity with company_id should use these filter names
 * so the AOP aspect can enable them uniformly.
 *
 * Usage on entity:
 * <pre>
 * {@code
 * @FilterDef(name = CompanyScopeFilterConstants.FILTER_NAME,
 *     parameters = @ParamDef(name = CompanyScopeFilterConstants.PARAM_NAME, type = Long.class))
 * @Filter(name = CompanyScopeFilterConstants.FILTER_NAME,
 *     condition = "company_id IN (:companyIds)")
 * @Entity
 * public class User { ... }
 * }
 * </pre>
 */
public final class CompanyScopeFilterConstants {

    private CompanyScopeFilterConstants() {
    }

    public static final String FILTER_NAME = "companyScope";
    public static final String PARAM_NAME = "companyIds";
}
