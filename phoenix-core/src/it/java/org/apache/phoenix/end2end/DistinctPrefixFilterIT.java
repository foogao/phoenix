package org.apache.phoenix.end2end;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DistinctPrefixFilterIT extends BaseHBaseManagedTimeTableReuseIT {
    private static String testTableF = generateRandomString();
    private static String testTableV = generateRandomString();
    private static String testSeq = testTableF + "_seq";
    private static Connection conn;

    @BeforeClass
    public static void doSetup() throws Exception {
        BaseHBaseManagedTimeTableReuseIT.doSetup();

        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        String ddl = "CREATE TABLE " + testTableF +
                "  (prefix1 INTEGER NOT NULL, prefix2 INTEGER NOT NULL, prefix3 INTEGER NOT NULL, " +
                "col1 FLOAT, CONSTRAINT pk PRIMARY KEY(prefix1, prefix2, prefix3))";
        createTestTable(getUrl(), ddl);

        ddl = "CREATE TABLE " + testTableV +
                "  (prefix1 varchar NOT NULL, prefix2 varchar NOT NULL, prefix3 INTEGER NOT NULL, " +
                "col1 FLOAT, CONSTRAINT pk PRIMARY KEY(prefix1, prefix2, prefix3))";
        createTestTable(getUrl(), ddl);

        conn.prepareStatement("CREATE SEQUENCE " + testSeq + " CACHE 1000").execute();

        insertPrefixF(1, 1);
        insertPrefixF(1, 2);
        insertPrefixF(1, 3);
        insertPrefixF(2, 1);
        insertPrefixF(2, 2);
        insertPrefixF(2, 3);
        insertPrefixF(3, 1);
        insertPrefixF(3, 2);
        insertPrefixF(2147483647, 2147483647); // all xFF
        insertPrefixF(3, 2147483647); // all xFF
        insertPrefixF(3, 3);
        conn.commit();

        insertPrefixV("1", "1");
        insertPrefixV("1", "2");
        insertPrefixV("1", "3");
        insertPrefixV("2", "1");
        insertPrefixV("2", "2");
        insertPrefixV("2", "3");
        insertPrefixV("22", "1");
        insertPrefixV("3", "22");
        insertPrefixV("3", "1");
        insertPrefixV("3", "2");
        insertPrefixV("3", "3");

        multiply();
        multiply();
        multiply();
        multiply();
        multiply();
        multiply();
        multiply();
        multiply();
        multiply(); // 512 per unique prefix
    }

    @Test
    public void testCornerCases() throws Exception {
        String testTable = generateRandomString();
        String ddl = "CREATE TABLE " + testTable +
                "  (prefix1 INTEGER NOT NULL, prefix2 SMALLINT NOT NULL, prefix3 INTEGER NOT NULL, " +
                "col1 FLOAT, CONSTRAINT pk PRIMARY KEY(prefix1, prefix2, prefix3))";
        createTestTable(getUrl(), ddl);

        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + testTable
                + "(prefix1, prefix2, prefix3, col1) VALUES(?,?,NEXT VALUE FOR "+testSeq+",rand())");
        stmt.setInt(1, 1);
        stmt.setInt(2, 2);
        stmt.execute();

        stmt = conn.prepareStatement("UPSERT INTO " + testTable
                + "(prefix1, prefix2, prefix3, col1) VALUES(?,?,NEXT VALUE FOR "+testSeq+",rand())");
        stmt.setInt(1, 2);
        stmt.setInt(2, 32767);
        stmt.execute();

        stmt = conn.prepareStatement("UPSERT INTO " + testTable
                + "(prefix1, prefix2, prefix3, col1) VALUES(?,?,NEXT VALUE FOR "+testSeq+",rand())");
        stmt.setInt(1, 3);
        stmt.setInt(2, 1);
        stmt.execute();

        stmt = conn.prepareStatement("UPSERT INTO " + testTable
                + "(prefix1, prefix2, prefix3, col1) VALUES(?,?,NEXT VALUE FOR "+testSeq+",rand())");
        stmt.setInt(1, 3);
        stmt.setInt(2, 2);
        stmt.execute();
        conn.commit();

        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1 ORDER BY prefix1 DESC", 3);
        testSkipRange("SELECT %s DISTINCT prefix1 FROM " + testTable + " ORDER BY prefix1 DESC", 3);
    }

    @Test
    public void testPlans() throws Exception {
        final String PREFIX = "SERVER DISTINCT PREFIX";

        // use the filter even when the SkipScan filter is used
        String dataSql = "SELECT DISTINCT prefix1, prefix2 FROM "+testTableF+ " WHERE prefix1 IN (1,2)";
        ResultSet rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(PREFIX));

        dataSql = "SELECT prefix1, 1, 2 FROM "+testTableF+" GROUP BY prefix1 HAVING prefix1 = 1";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(PREFIX));

        dataSql = "SELECT prefix1 FROM "+testTableF+" GROUP BY prefix1, TRUNC(prefix1), TRUNC(prefix2)";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(PREFIX));

        dataSql = "SELECT DISTINCT prefix1, prefix2 FROM "+testTableV+ " WHERE prefix1 IN ('1','2')";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(PREFIX));

        dataSql = "SELECT prefix1, 1, 2 FROM "+testTableV+" GROUP BY prefix1 HAVING prefix1 = '1'";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(PREFIX));

        testCommonPlans(testTableF, PREFIX);
        testCommonPlans(testTableV, PREFIX);
    }

    private void testCommonPlans(String testTable, String contains) throws Exception {

        String dataSql = "SELECT DISTINCT prefix1 FROM "+testTable;
        ResultSet rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT /*+ RANGE_SCAN */ DISTINCT prefix1 FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT DISTINCT prefix1, prefix2 FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));

        // use the filter even when the boolean expression filter is used
        dataSql = "SELECT DISTINCT prefix1, prefix2 FROM "+testTable+ " WHERE col1 > 0.5";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));

        // do not use the filter when the distinct is on the entire key
        dataSql = "SELECT DISTINCT prefix1, prefix2, prefix3 FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT DISTINCT (prefix1, prefix2, prefix3) FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT DISTINCT prefix1, prefix2, col1, prefix3 FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT DISTINCT prefix1, prefix2, col1 FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT DISTINCT col1, prefix1, prefix2 FROM "+testTable;
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT prefix1 FROM "+testTable+" GROUP BY prefix1";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT prefix1, count(*) FROM "+testTable+" GROUP BY prefix1";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT prefix1 FROM "+testTable+" GROUP BY prefix1, prefix2";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT prefix1 FROM "+testTable+" GROUP BY prefix1, prefix2, prefix3";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT (prefix1, prefix2, prefix3) FROM "+testTable+" GROUP BY (prefix1, prefix2, prefix3)";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT prefix1, 1, 2 FROM "+testTable+" GROUP BY prefix1";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT prefix1 FROM "+testTable+" GROUP BY prefix1, col1";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertFalse(QueryUtil.getExplainPlan(rs).contains(contains));

        dataSql = "SELECT DISTINCT prefix1, prefix2 FROM "+testTable+" WHERE col1 > 0.5";
        rs = conn.createStatement().executeQuery("EXPLAIN "+dataSql);
        assertTrue(QueryUtil.getExplainPlan(rs).contains(contains));
    }

    @Test
    public void testGroupBy() throws Exception {
        testSkipRange("SELECT %s prefix1 FROM "+ testTableF + " GROUP BY prefix1, prefix2 HAVING prefix1 IN (1,2)", 6);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableF + " GROUP BY prefix1, prefix2 HAVING prefix1 IN (1,2) AND prefix2 IN (1,2)", 4);
        // this leads to a scan along [prefix1,prefix2], but work correctly
        testSkipRange("SELECT %s prefix1 FROM "+ testTableF + " GROUP BY prefix1, prefix2 HAVING prefix2 = 2", 3);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableF + " GROUP BY prefix1, prefix2 HAVING prefix2 = 2147483647", 2);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableF + " GROUP BY prefix1, prefix2 HAVING prefix1 = 2147483647", 1);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableF + " WHERE col1 > 0.99 GROUP BY prefix1, prefix2 HAVING prefix2 = 2", -1);

        testSkipRange("SELECT %s prefix1 FROM "+ testTableV + " GROUP BY prefix1, prefix2 HAVING prefix1 IN ('1','2')", 6);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableV + " GROUP BY prefix1, prefix2 HAVING prefix1 IN ('1','2') AND prefix2 IN ('1','2')", 4);
        // this leads to a scan along [prefix1,prefix2], but work correctly
        testSkipRange("SELECT %s prefix1 FROM "+ testTableV + " GROUP BY prefix1, prefix2 HAVING prefix2 = '2'", 3);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableV + " GROUP BY prefix1, prefix2 HAVING prefix2 = '22'", 1);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableV + " GROUP BY prefix1, prefix2 HAVING prefix1 = '22'", 1);
        testSkipRange("SELECT %s prefix1 FROM "+ testTableV + " WHERE col1 > 0.99 GROUP BY prefix1, prefix2 HAVING prefix2 = '2'", -1);

        testCommonGroupBy(testTableF);
        testCommonGroupBy(testTableV);
    }

    private void testCommonGroupBy(String testTable) throws Exception {
        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1", 4);
        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1 ORDER BY prefix1 DESC", 4);
        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1, prefix2", 11);
        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1, prefix2 ORDER BY prefix1 DESC", 11);
        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1, prefix2 ORDER BY prefix2 DESC", 11);
        testSkipRange("SELECT %s prefix1 FROM "+ testTable + " GROUP BY prefix1, prefix2 ORDER BY prefix1, prefix2 DESC", 11);
    }

    @Test
    public void testDistinct() throws Exception {
        // mix distinct prefix and SkipScan filters
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableF + " WHERE prefix1 IN (1,2)", 6);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableF + " WHERE prefix1 IN (3,2147483647)", 5);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableF + " WHERE prefix1 IN (3,2147483647) ORDER BY prefix1 DESC", 5);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableF + " WHERE prefix1 IN (3,2147483647) ORDER BY prefix2 DESC", 5);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableF + " WHERE prefix1 IN (2147483647,2147483647)", 1);
        // mix distinct and boolean expression filters
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableF + " WHERE col1 > 0.99 AND prefix1 IN (1,2)", -1);

        // mix distinct prefix and SkipScan filters
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableV + " WHERE prefix1 IN ('1','2')", 6);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableV + " WHERE prefix1 IN ('3','22')", 5);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableV + " WHERE prefix1 IN ('3','22') ORDER BY prefix1 DESC", 5);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableV + " WHERE prefix1 IN ('3','22') ORDER BY prefix2 DESC", 5);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableV + " WHERE prefix1 IN ('2','22')", 4);
        // mix distinct and boolean expression filters
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTableV + " WHERE col1 > 0.99 AND prefix1 IN ('1','2')", -1);

        testCommonDistinct(testTableF);
        testCommonDistinct(testTableV);
}

    private void testCommonDistinct(String testTable) throws Exception {
        testSkipRange("SELECT %s DISTINCT prefix1 FROM " + testTable, 4);
        testSkipRange("SELECT %s DISTINCT prefix1 FROM " + testTable + " ORDER BY prefix1 DESC", 4);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTable, 11);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTable + " ORDER BY prefix1 DESC", 11);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTable + " ORDER BY prefix2 DESC", 11);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTable + " ORDER BY prefix1, prefix2 DESC", 11);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTable + " WHERE col1 > 0.99", -1);
        testSkipRange("SELECT %s DISTINCT prefix1, prefix2 FROM " + testTable + " WHERE col1 > 0.99 ORDER BY prefix1, prefix2 DESC", -1);
    }

    @Test
    public void testRVC() throws Exception {
        String q = "SELECT (prefix1, prefix2) FROM "+ testTableF + " GROUP BY (prefix1, prefix2)";
        PreparedStatement stmt = conn.prepareStatement(q);
        ResultSet res = stmt.executeQuery();
        byte[] r1 = null;
        res.next();
        r1 = (byte[])res.getObject(1);
        assertFalse(res.next());
        q = "SELECT /*+ RANGE_SCAN */ (prefix1, prefix2) FROM "+ testTableF + " GROUP BY (prefix1, prefix2)";
        stmt = conn.prepareStatement(q);
        res = stmt.executeQuery();
        byte[] r2 = null;
        res.next();
        r2 = (byte[])res.getObject(1);
        assertFalse(res.next());
        assertTrue(Bytes.equals(r1, r2));

        q = "SELECT DISTINCT(prefix1, prefix2) FROM "+ testTableF;
        stmt = conn.prepareStatement(q);
        res = stmt.executeQuery();
        r1 = null;
        res.next();
        r1 = (byte[])res.getObject(1);
        assertFalse(res.next());

        q = "SELECT /*+ RANGE_SCAN */ DISTINCT(prefix1, prefix2) FROM "+ testTableF;
        stmt = conn.prepareStatement(q);
        res = stmt.executeQuery();
        r2 = null;
        res.next();
        r2 = (byte[])res.getObject(1);
        assertFalse(res.next());
        assertTrue(Bytes.equals(r1, r2));
    }

    private void testSkipRange(String q, int expected) throws SQLException {
        String q1 = String.format(q, "");
        PreparedStatement stmt = conn.prepareStatement(q1);
        ResultSet res = stmt.executeQuery();
        int count = 0;
        while(res.next()) {
            count++;
        }

        if (expected > 0) assertEquals(expected, count);

        q1 = String.format(q, "/*+ RANGE_SCAN */");
        stmt = conn.prepareStatement(q1);
        res = stmt.executeQuery();
        int count1 = 0;
        while(res.next()) {
            count1++;
        }
        assertEquals(count, count1);
    }

    private static void insertPrefixF(int prefix1, int prefix2) throws SQLException {
        String query = "UPSERT INTO " + testTableF
                + "(prefix1, prefix2, prefix3, col1) VALUES(?,?,NEXT VALUE FOR "+testSeq+",rand())";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setInt(1, prefix1);
            stmt.setInt(2, prefix2);
            stmt.execute();
    }

    private static void insertPrefixV(String prefix1, String prefix2) throws SQLException {
        String query = "UPSERT INTO " + testTableV
                + "(prefix1, prefix2, prefix3, col1) VALUES(?,?,NEXT VALUE FOR "+testSeq+",rand())";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setString(1, prefix1);
            stmt.setString(2, prefix2);
            stmt.execute();
    }

    private static void multiply() throws SQLException {
        conn.prepareStatement("UPSERT INTO " + testTableF
                + " SELECT prefix1,prefix2,NEXT VALUE FOR "+testSeq+",rand() FROM "+testTableF).execute();
        conn.prepareStatement("UPSERT INTO " + testTableV
                + " SELECT prefix1,prefix2,NEXT VALUE FOR "+testSeq+",rand() FROM "+testTableV).execute();
        conn.commit();
    }
}
