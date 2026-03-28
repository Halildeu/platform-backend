package com.example.report.query;

import com.example.report.registry.AggregateSpec;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class AggregateSqlBuilder {

    public record BuiltQuery(String sql, MapSqlParameterSource params) {}

    public BuiltQuery buildAggregateQuery(String source,
                                           String sourceSchema,
                                           AggregateSpec aggregate,
                                           String rlsWhereClause,
                                           MapSqlParameterSource rlsParams,
                                           MapSqlParameterSource timeParams) {
        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        sql.append("SELECT ").append(aggregate.select());
        sql.append(" FROM [").append(sourceSchema).append("].[").append(source).append("] WITH (NOLOCK)");

        if (aggregate.join() != null && !aggregate.join().isBlank()) {
            sql.append(" ").append(aggregate.join());
        }

        sql.append(" WHERE 1=1");

        if (rlsWhereClause != null && !rlsWhereClause.isBlank()) {
            sql.append(" AND ").append(rlsWhereClause);
            if (rlsParams != null) {
                mergeParams(params, rlsParams);
            }
        }

        if (aggregate.where() != null && !aggregate.where().isBlank()) {
            sql.append(" AND ").append(aggregate.where());
        }

        if (timeParams != null) {
            mergeParams(params, timeParams);
        }

        if (aggregate.groupBy() != null && !aggregate.groupBy().isBlank()) {
            sql.append(" GROUP BY ").append(aggregate.groupBy());
        }

        if (aggregate.orderBy() != null && !aggregate.orderBy().isBlank()) {
            sql.append(" ORDER BY ").append(aggregate.orderBy());
        }

        if (aggregate.limit() != null && aggregate.limit() > 0) {
            if (aggregate.orderBy() == null || aggregate.orderBy().isBlank()) {
                sql.append(" ORDER BY (SELECT NULL)");
            }
            sql.append(" OFFSET 0 ROWS FETCH NEXT :_aggLimit ROWS ONLY");
            params.addValue("_aggLimit", aggregate.limit());
        }

        return new BuiltQuery(sql.toString(), params);
    }

    public static MapSqlParameterSource buildTimeParams(String timeRange) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = resolveStart(timeRange, now);

        params.addValue("_periodStart", periodStart);
        params.addValue("_periodEnd", now);

        long days = ChronoUnit.DAYS.between(periodStart, now);
        LocalDateTime prevPeriodEnd = periodStart.minusDays(1);
        LocalDateTime prevPeriodStart = prevPeriodEnd.minusDays(days);

        params.addValue("_prevPeriodStart", prevPeriodStart);
        params.addValue("_prevPeriodEnd", prevPeriodEnd);
        params.addValue("_trendPeriodStart", prevPeriodStart);

        return params;
    }

    private static LocalDateTime resolveStart(String timeRange, LocalDateTime now) {
        if (timeRange == null) {
            return now.minusDays(90);
        }
        return switch (timeRange.toLowerCase()) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            case "180d" -> now.minusDays(180);
            case "1y" -> now.minusYears(1);
            case "ytd" -> LocalDate.now().atStartOfDay().withDayOfYear(1);
            default -> now.minusDays(90);
        };
    }

    private void mergeParams(MapSqlParameterSource target, MapSqlParameterSource source) {
        source.getValues().forEach(target::addValue);
    }
}
