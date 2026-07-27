package com.example.report.workcube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ProjectOptionsRepositoryTest {

    @Mock JdbcTemplate jdbc;

    @Test
    void findsProjectsThroughCanonicalOurCompanyRelationship() {
        when(jdbc.query(any(String.class), any(RowMapper.class), eq(35L)))
                .thenReturn(List.of(
                        new ProjectOptionsRepository.ProjectOption(
                                91L, "IL05", "Equinix IL05.1", 35L, true)));

        var result = new ProjectOptionsRepository(jdbc).findByCompanyId(35L);

        assertEquals(1, result.size());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(35L));
        assertTrue(sql.getValue().contains("[workcube_mikrolink].[PRO_PROJECTS]"));
        assertTrue(sql.getValue().contains("cmp.OUR_COMPANY_ID"));
        assertTrue(sql.getValue().contains("oc.COMP_ID = ?"));
        assertTrue(sql.getValue().contains("p.PROJECT_NUMBER"));
    }
}
