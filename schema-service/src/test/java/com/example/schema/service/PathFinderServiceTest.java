package com.example.schema.service;

import com.example.schema.model.Relationship;
import com.example.schema.service.PathFinderService.PathResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathFinderServiceTest {

    private PathFinderService service;
    private List<Relationship> relationships;

    @BeforeEach
    void setUp() {
        service = new PathFinderService();
        relationships = List.of(
            new Relationship("ORDER_ROW", "ORDER_ID", "ORDERS", "ORDER_ID", 0.95, "name_match"),
            new Relationship("ORDERS", "COMPANY_ID", "COMPANY", "COMPANY_ID", 0.92, "common_fk"),
            new Relationship("COMPANY", "COMPANY_CAT_ID", "COMPANY_CAT", "COMPANY_CAT_ID", 0.85, "name_match"),
            new Relationship("ORDER_ROW", "PRODUCT_ID", "PRODUCTS", "PRODUCT_ID", 0.90, "common_fk"),
            new Relationship("COMPANY_BANK", "COMPANY_ID", "COMPANY", "COMPANY_ID", 0.97, "common_fk")
        );
    }

    @Test
    void findDirectPath() {
        PathResult result = service.findPath("COMPANY_BANK", "COMPANY", relationships);
        assertEquals(1, result.hops());
        assertEquals("COMPANY_BANK", result.from());
        assertEquals("COMPANY", result.to());
        assertFalse(result.path().isEmpty());
        assertTrue(result.joinSql().contains("JOIN COMPANY"));
    }

    @Test
    void findTwoHopPath() {
        PathResult result = service.findPath("ORDER_ROW", "COMPANY", relationships);
        assertEquals(2, result.hops());
        assertEquals("ORDER_ROW", result.from());
        assertEquals("COMPANY", result.to());
        assertTrue(result.joinSql().contains("JOIN"));
    }

    @Test
    void findThreeHopPath() {
        PathResult result = service.findPath("ORDER_ROW", "COMPANY_CAT", relationships);
        assertEquals(3, result.hops());
    }

    @Test
    void sameTableReturnsZeroHops() {
        PathResult result = service.findPath("COMPANY", "COMPANY", relationships);
        assertEquals(0, result.hops());
        assertTrue(result.path().isEmpty());
    }

    @Test
    void noPathReturnsNegativeHops() {
        PathResult result = service.findPath("COMPANY", "NONEXISTENT", relationships);
        assertEquals(-1, result.hops());
    }

    @Test
    void findPathGeneratesValidSql() {
        PathResult result = service.findPath("COMPANY_BANK", "COMPANY", relationships);
        assertNotNull(result.joinSql());
        assertTrue(result.joinSql().contains("SELECT"));
        assertTrue(result.joinSql().contains("JOIN"));
        assertTrue(result.joinSql().contains("COMPANY_ID"));
    }

    /**
     * Codex 01a08afc P1 (gitops#3631): a composite key joined on its representative pair alone
     * returned rows of every parent sharing that value — CHILD (A, 7) matched both (A, 7) and
     * (B, 7). Every pair is joined, in both directions of the edge.
     */
    @Test
    void compositeEdgeJoinsOnEveryColumnPair() {
        List<Relationship> rels = List.of(
            new Relationship("CHILD", "EMP_NO", "COMPANY_PERSON", "EMP_NO", 1.0, "fk_constraint_composite", false,
                List.of("COMPANY", "EMP_NO"), List.of("COMPANY", "EMP_NO")),
            new Relationship("COMPANY_PERSON", "COMPANY", "COMPANY", "COMPANY", 1.0, "fk_constraint"));

        PathResult forward = service.findPath("CHILD", "COMPANY_PERSON", rels);
        assertEquals("SELECT *\nFROM CHILD t0\nJOIN COMPANY_PERSON t1 ON t0.COMPANY = t1.COMPANY AND t0.EMP_NO = t1.EMP_NO",
            forward.joinSql());
        assertEquals("EMP_NO", forward.path().getFirst().column(), "the step shows the representative pair");

        PathResult reverse = service.findPath("COMPANY_PERSON", "CHILD", rels);
        assertEquals("SELECT *\nFROM COMPANY_PERSON t0\nJOIN CHILD t1 ON t0.COMPANY = t1.COMPANY AND t0.EMP_NO = t1.EMP_NO",
            reverse.joinSql());

        List<PathResult> all = service.findAllPaths("CHILD", "COMPANY", rels, 3);
        assertFalse(all.isEmpty());
        assertTrue(all.getFirst().joinSql().contains("t0.COMPANY = t1.COMPANY AND t0.EMP_NO = t1.EMP_NO"), all.getFirst().joinSql());
        assertTrue(all.getFirst().joinSql().contains("JOIN COMPANY t2 ON t1.COMPANY = t2.COMPANY"), all.getFirst().joinSql());
    }
}
