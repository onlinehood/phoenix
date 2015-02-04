/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.apache.phoenix.util.TestUtil.INDEX_DATA_SCHEMA;
import static org.apache.phoenix.util.TestUtil.INDEX_DATA_TABLE;
import static org.apache.phoenix.util.TestUtil.MUTABLE_INDEX_DATA_TABLE;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.phoenix.end2end.BaseHBaseManagedTimeIT;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.junit.Test;

public class IndexExpressionIT extends BaseHBaseManagedTimeIT {

    private static final int NUM_MILLIS_IN_DAY = 86400000;

    @Test
    public void testImmutableIndexCreationAndUpdate() throws Exception {
        helpTestCreateAndUpdate(false, false);
    }

    @Test
    public void testImmutableLocalIndexCreationAndUpdate() throws Exception {
        helpTestCreateAndUpdate(false, true);
    }

    @Test
    public void testMutableIndexCreationAndUpdate() throws Exception {
        helpTestCreateAndUpdate(true, false);
    }

    @Test
    public void testMutableLocalIndexCreationAndUpdate() throws Exception {
        helpTestCreateAndUpdate(true, true);
    }

    /**
     * Adds a row to the index data table
     * 
     * @param i
     *            row number
     */
    private void insertRow(PreparedStatement stmt, int i) throws SQLException {
        // insert row
        stmt.setString(1, "varchar" + String.valueOf(i));
        stmt.setString(2, "char" + String.valueOf(i));
        stmt.setInt(3, i);
        stmt.setLong(4, i);
        stmt.setBigDecimal(5, new BigDecimal(Double.valueOf(i)));
        Date date = new Date(DateUtil.parseDate("2015-01-01 00:00:00").getTime() + (i - 1) * NUM_MILLIS_IN_DAY);
        stmt.setDate(6, date);
        stmt.setString(7, "a.varchar" + String.valueOf(i));
        stmt.setString(8, "a.char" + String.valueOf(i));
        stmt.setInt(9, i);
        stmt.setLong(10, i);
        stmt.setBigDecimal(11, new BigDecimal((double)i));
        stmt.setDate(12, date);
        stmt.setString(13, "b.varchar" + String.valueOf(i));
        stmt.setString(14, "b.char" + String.valueOf(i));
        stmt.setInt(15, i);
        stmt.setLong(16, i);
        stmt.setBigDecimal(17, new BigDecimal((double)i));
        stmt.setDate(18, date);
        stmt.executeUpdate();
    }

    private void verifyResult(ResultSet rs, int i) throws SQLException {
        assertTrue(rs.next());
        assertEquals("VARCHAR" + String.valueOf(i) + "_" + StringUtils.rightPad("CHAR" + String.valueOf(i), 6, ' ')
                + "_A.VARCHAR" + String.valueOf(i) + "_" + StringUtils.rightPad("B.CHAR" + String.valueOf(i), 10, ' '),
                rs.getString(1));
        assertEquals(i * 4, rs.getInt(2));
        Date date = new Date(DateUtil.parseDate("2015-01-01 00:00:00").getTime() + (i) * NUM_MILLIS_IN_DAY);
        assertEquals(date, rs.getDate(3));
        assertEquals(date, rs.getDate(4));
        assertEquals(date, rs.getDate(5));
        assertEquals("varchar" + String.valueOf(i), rs.getString(6));
        assertEquals("char" + String.valueOf(i), rs.getString(7));
        assertEquals(i, rs.getInt(8));
        assertEquals(i, rs.getLong(9));
        assertEquals(i, rs.getDouble(10), 0.000001);
        assertEquals(i, rs.getLong(11));
        assertEquals(i, rs.getLong(12));
    }

    protected void helpTestCreateAndUpdate(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            populateDataTable(conn, dataTableName);

            // create an expression index
            String ddl = "CREATE "
                    + (localIndex ? "LOCAL" : "")
                    + " INDEX IDX ON "
                    + fullDataTableName
                    + " ((UPPER(varchar_pk) || '_' || UPPER(char_pk) || '_' || UPPER(varchar_col1) || '_' || UPPER(char_col2)),"
                    + " (decimal_pk+int_pk+decimal_col2+int_col1)," + " date_pk+1, date1+1, date2+1 )"
                    + " INCLUDE (long_col1, long_col2)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();

            // run select query with expression in WHERE clause
            String whereSql = "SELECT long_col1, long_col2 from "
                    + fullDataTableName
                    + " WHERE UPPER(varchar_pk) || '_' || UPPER(char_pk) || '_' || UPPER(varchar_col1) || '_' || UPPER(char_col2) = ?"
                    + " AND decimal_pk+int_pk+decimal_col2+int_col1=?"
                    // since a.date1 and b.date2 are NULLABLE and date is fixed width, these expressions are stored as
                    // DECIMAL in the index (which is not fixed width)
                    + " AND date_pk+1=? AND date1+1=? AND date2+1=?";
            stmt = conn.prepareStatement(whereSql);
            stmt.setString(1, "VARCHAR1_CHAR1 _A.VARCHAR1_B.CHAR1   ");
            stmt.setInt(2, 4);
            Date date = DateUtil.parseDate("2015-01-02 00:00:00");
            stmt.setDate(3, date);
            stmt.setDate(4, date);
            stmt.setDate(5, date);

            // verify that the query does a range scan on the index table
            ResultSet rs = stmt.executeQuery("EXPLAIN " + whereSql);
            assertEquals(
                    localIndex ? "CLIENT PARALLEL 1-WAY RANGE SCAN OVER _LOCAL_IDX_INDEX_TEST."
                            + dataTableName
                            + " [-32768,'VARCHAR1_CHAR1 _A.VARCHAR1_B.CHAR1   ',4,'2015-01-02 00:00:00.000',1,420,156,800,000,1,420,156,800,000]\nCLIENT MERGE SORT"
                            : "CLIENT PARALLEL 1-WAY RANGE SCAN OVER INDEX_TEST.IDX ['VARCHAR1_CHAR1 _A.VARCHAR1_B.CHAR1   ',4,'2015-01-02 00:00:00.000',1,420,156,800,000,1,420,156,800,000]",
                    QueryUtil.getExplainPlan(rs));

            // verify that the correct results are returned
            rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
            assertFalse(rs.next());

            // verify all rows in data table are present in index table
            String indexSelectSql = "SELECT UPPER(varchar_pk) || '_' || UPPER(char_pk) || '_' || UPPER(varchar_col1) || '_' || UPPER(char_col2), "
                    + "decimal_pk+int_pk+decimal_col2+int_col1, "
                    + "date_pk+1, date1+1, date2+1, "
                    + "varchar_pk, char_pk, int_pk, long_pk, decimal_pk, "
                    + "long_col1, long_col2 "
                    + "from "
                    + fullDataTableName;
            rs = conn.createStatement().executeQuery("EXPLAIN " + indexSelectSql);
            assertEquals(localIndex ? "CLIENT PARALLEL 1-WAY RANGE SCAN OVER _LOCAL_IDX_" + fullDataTableName
                    + " [-32768]\nCLIENT MERGE SORT" : "CLIENT PARALLEL 1-WAY FULL SCAN OVER INDEX_TEST.IDX",
                    QueryUtil.getExplainPlan(rs));
            rs = conn.createStatement().executeQuery(indexSelectSql);
            verifyResult(rs, 1);
            verifyResult(rs, 2);

            // Insert two more rows to the index data table
            String upsert = "UPSERT INTO " + fullDataTableName
                    + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            stmt = conn.prepareStatement(upsert);
            insertRow(stmt, 3);
            insertRow(stmt, 4);
            conn.commit();

            rs = conn.createStatement().executeQuery(indexSelectSql);
            verifyResult(rs, 1);
            verifyResult(rs, 2);
            // verify that two rows added after index was created were also added to
            // the index table
            verifyResult(rs, 3);
            verifyResult(rs, 4);

            // update the first row
            upsert = "UPSERT INTO "
                    + fullDataTableName
                    + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, a.varchar_col1) VALUES(?, ?, ?, ?, ?, ?, ?)";

            stmt = conn.prepareStatement(upsert);
            stmt.setString(1, "varchar1");
            stmt.setString(2, "char1");
            stmt.setInt(3, 1);
            stmt.setLong(4, 1l);
            stmt.setBigDecimal(5, new BigDecimal(1.0));
            stmt.setDate(6, DateUtil.parseDate("2015-01-01 00:00:00"));
            stmt.setString(7, "a.varchar_updated");
            stmt.executeUpdate();
            conn.commit();

            // verify only one row was updated in the data table
            String selectSql = "UPPER(varchar_pk) || '_' || UPPER(char_pk) || '_' || UPPER(varchar_col1) || '_' || UPPER(char_col2) from "
                    + fullDataTableName;
            rs = conn.createStatement().executeQuery("SELECT /*+ NO_INDEX */ " + selectSql);
            assertTrue(rs.next());
            assertEquals("VARCHAR1_CHAR1 _A.VARCHAR_UPDATED_B.CHAR1   ", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VARCHAR2_CHAR2 _A.VARCHAR2_B.CHAR2   ", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VARCHAR3_CHAR3 _A.VARCHAR3_B.CHAR3   ", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VARCHAR4_CHAR4 _A.VARCHAR4_B.CHAR4   ", rs.getString(1));
            assertFalse(rs.next());

            // verify that the rows in the index table are also updated
            rs = conn.createStatement().executeQuery("SELECT " + selectSql);
            assertTrue(rs.next());
            // if the data table is immutable, the index table will have one more
            // row
            if (!mutable) {
                assertEquals("VARCHAR1_CHAR1 _A.VARCHAR1_B.CHAR1   ", rs.getString(1));
                assertTrue(rs.next());
            }
            assertEquals("VARCHAR1_CHAR1 _A.VARCHAR_UPDATED_" + (mutable ? "B.CHAR1   " : ""), rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VARCHAR2_CHAR2 _A.VARCHAR2_B.CHAR2   ", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VARCHAR3_CHAR3 _A.VARCHAR3_B.CHAR3   ", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VARCHAR4_CHAR4 _A.VARCHAR4_B.CHAR4   ", rs.getString(1));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    private void populateDataTable(Connection conn, String dataTable) throws SQLException {
        ensureTableCreated(getUrl(), dataTable);
        String upsert = "UPSERT INTO " + INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTable
                + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement stmt1 = conn.prepareStatement(upsert);
        // insert two rows
        insertRow(stmt1, 1);
        insertRow(stmt1, 2);
        conn.commit();
    }

    @Test
    public void testDeleteIndexedExpressionImmutableIndex() throws Exception {
        helpTestDeleteIndexedExpression(false, false);
    }

    @Test
    public void testDeleteIndexedExpressionImmutableLocalIndex() throws Exception {
        helpTestDeleteIndexedExpression(false, true);
    }

    @Test
    public void testDeleteIndexedExpressionMutableIndex() throws Exception {
        helpTestDeleteIndexedExpression(true, false);
    }

    @Test
    public void testDeleteIndexedExpressionMutableLocalIndex() throws Exception {
        helpTestDeleteIndexedExpression(true, true);
    }

    protected void helpTestDeleteIndexedExpression(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        String fullIndexTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + "IDX";
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            ensureTableCreated(getUrl(), dataTableName);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (2*long_col2)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();

            ResultSet rs;
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullDataTableName);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullIndexTableName);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));

            conn.setAutoCommit(true);
            String dml = "DELETE from " + fullDataTableName + " WHERE long_col2 = 2";
            try {
                conn.createStatement().execute(dml);
                if (!mutable) {
                    fail();
                }
            } catch (SQLException e) {
                if (!mutable) {
                    assertEquals(SQLExceptionCode.INVALID_FILTER_ON_IMMUTABLE_ROWS.getErrorCode(), e.getErrorCode());
                }
            }

            if (!mutable) {
                dml = "DELETE from " + fullDataTableName + " WHERE 2*long_col2 = 4";
                conn.createStatement().execute(dml);
            }

            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullDataTableName);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullIndexTableName);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    @Test
    public void testDeleteCoveredColImmutableIndex() throws Exception {
        helpTestDeleteCoveredCol(false, false);
    }

    @Test
    public void testDeleteCoveredColImmutableLocalIndex() throws Exception {
        helpTestDeleteCoveredCol(false, true);
    }

    @Test
    public void testDeleteCoveredColMutableIndex() throws Exception {
        helpTestDeleteCoveredCol(true, false);
    }

    @Test
    public void testDeleteCoveredColMutableLocalIndex() throws Exception {
        helpTestDeleteCoveredCol(true, true);
    }

    protected void helpTestDeleteCoveredCol(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        String fullIndexTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + "IDX";
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            ensureTableCreated(getUrl(), dataTableName);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (long_pk, varchar_pk, 1+long_pk, UPPER(varchar_pk) )" + " INCLUDE (long_col1, long_col2)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();

            ResultSet rs;
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullDataTableName);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullIndexTableName);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));

            String dml = "DELETE from " + fullDataTableName + " WHERE long_col2 = 2";
            assertEquals(1, conn.createStatement().executeUpdate(dml));
            conn.commit();

            String query = "SELECT /*+ NO_INDEX */ long_pk, varchar_pk, 1+long_pk, UPPER(varchar_pk) FROM "
                    + fullDataTableName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals(1L, rs.getLong(1));
            assertEquals("varchar1", rs.getString(2));
            assertEquals(2L, rs.getLong(3));
            assertEquals("VARCHAR1", rs.getString(4));
            assertFalse(rs.next());

            query = "SELECT long_pk, varchar_pk, 1+long_pk, UPPER(varchar_pk) FROM " + fullDataTableName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals(1L, rs.getLong(1));
            assertEquals("varchar1", rs.getString(2));
            assertEquals(2L, rs.getLong(3));
            assertEquals("VARCHAR1", rs.getString(4));
            assertFalse(rs.next());

            query = "SELECT * FROM " + fullIndexTableName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());

            assertEquals(1L, rs.getLong(1));
            assertEquals("varchar1", rs.getString(2));
            assertEquals(2L, rs.getLong(3));
            assertEquals("VARCHAR1", rs.getString(4));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    @Test
    public void testGroupByCountImmutableIndex() throws Exception {
        helpTestGroupByCount(false, false);
    }

    @Test
    public void testGroupByCountImmutableLocalIndex() throws Exception {
        helpTestGroupByCount(false, true);
    }

    @Test
    public void testGroupByCountMutableIndex() throws Exception {
        helpTestGroupByCount(true, false);
    }

    @Test
    public void testGroupByCountMutableLocalIndex() throws Exception {
        helpTestGroupByCount(true, true);
    }

    protected void helpTestGroupByCount(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (int_col1+int_col2)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();

            String groupBySql = "SELECT (int_col1+int_col2), COUNT(*) FROM " + fullDataTableName
                    + " GROUP BY (int_col1+int_col2)";
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + groupBySql);
            String expectedPlan = "CLIENT PARALLEL 1-WAY "
                    + (localIndex ? "RANGE SCAN OVER _LOCAL_IDX_" + fullDataTableName + " [-32768]"
                            : "FULL SCAN OVER INDEX_TEST.IDX")
                    + "\n    SERVER FILTER BY FIRST KEY ONLY\n    SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [TO_BIGINT((A.INT_COL1 + B.INT_COL2))]\nCLIENT MERGE SORT";
            assertEquals(expectedPlan, QueryUtil.getExplainPlan(rs));
            rs = conn.createStatement().executeQuery(groupBySql);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(2));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    @Test
    public void testSelectDistinctImmutableIndex() throws Exception {
        helpTestSelectDistinct(false, false);
    }

    @Test
    public void testSelectDistinctImmutableIndexLocal() throws Exception {
        helpTestSelectDistinct(false, true);
    }

    @Test
    public void testSelectDistinctMutableIndex() throws Exception {
        helpTestSelectDistinct(true, false);
    }

    @Test
    public void testSelectDistinctMutableLocalIndex() throws Exception {
        helpTestSelectDistinct(true, true);
    }

    protected void helpTestSelectDistinct(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (int_col1+1)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            String sql = "SELECT distinct int_col1+1 FROM " + fullDataTableName + " where int_col1+1 > 0";
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + sql);
            String expectedPlan = "CLIENT PARALLEL 1-WAY RANGE SCAN OVER "
                    + (localIndex ? "_LOCAL_IDX_" + fullDataTableName + " [-32768,0] - [-32768,*]"
                            : "INDEX_TEST.IDX [0] - [*]")
                    + "\n    SERVER FILTER BY FIRST KEY ONLY\n    SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [TO_BIGINT((A.INT_COL1 + 1))]\nCLIENT MERGE SORT";
            assertEquals(expectedPlan, QueryUtil.getExplainPlan(rs));
            rs = conn.createStatement().executeQuery(sql);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    @Test
    public void testInClauseWithImmutableIndex() throws Exception {
        helpTestInClauseWithIndex(false, false);
    }

    @Test
    public void testInClauseWithImmutableLocalIndex() throws Exception {
        helpTestInClauseWithIndex(false, true);
    }

    @Test
    public void testInClauseWithMutableIndex() throws Exception {
        helpTestInClauseWithIndex(true, false);
    }

    @Test
    public void testInClauseWithMutableLocalIndex() throws Exception {
        helpTestInClauseWithIndex(true, false);
    }

    protected void helpTestInClauseWithIndex(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (int_col1+1)";

            conn = DriverManager.getConnection(getUrl(), props);
            conn.setAutoCommit(false);
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            String sql = "SELECT int_col1+1 FROM " + fullDataTableName + " where int_col1+1 IN (2)";
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + sql);
            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER "
                    + (localIndex ? "_LOCAL_IDX_" + fullDataTableName + " [-32768,2]\n    SERVER FILTER BY FIRST KEY ONLY\nCLIENT MERGE SORT"
                            : "INDEX_TEST.IDX [2]\n    SERVER FILTER BY FIRST KEY ONLY"), QueryUtil.getExplainPlan(rs));
            rs = conn.createStatement().executeQuery(sql);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    @Test
    public void testOrderByWithImmutableIndex() throws Exception {
        helpTestOrderByWithIndex(false, false);
    }

    @Test
    public void testOrderByWithImmutableLocalIndex() throws Exception {
        helpTestOrderByWithIndex(false, true);
    }

    @Test
    public void testOrderByWithMutableIndex() throws Exception {
        helpTestOrderByWithIndex(true, false);
    }

    @Test
    public void testOrderByWithMutableLocalIndex() throws Exception {
        helpTestOrderByWithIndex(true, false);
    }

    protected void helpTestOrderByWithIndex(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (int_col1+1)";

            conn = DriverManager.getConnection(getUrl(), props);
            conn.setAutoCommit(false);
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            String sql = "SELECT int_col1+1 FROM " + fullDataTableName + " ORDER BY int_col1+1";
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + sql);
            assertEquals("CLIENT PARALLEL 1-WAY "
                    + (localIndex ? "RANGE SCAN OVER _LOCAL_IDX_" + fullDataTableName
                            + " [-32768]\n    SERVER FILTER BY FIRST KEY ONLY\nCLIENT MERGE SORT"
                            : "FULL SCAN OVER INDEX_TEST.IDX\n    SERVER FILTER BY FIRST KEY ONLY"),
                    QueryUtil.getExplainPlan(rs));
            rs = conn.createStatement().executeQuery(sql);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }

    @Test
    public void testSelectColOnlyInDataTableImmutableIndex() throws Exception {
        helpTestSelectColOnlyInDataTable(false, false);
    }

    @Test
    public void testSelectColOnlyInDataTableImmutableLocalIndex() throws Exception {
        helpTestSelectColOnlyInDataTable(false, true);
    }

    @Test
    public void testSelectColOnlyInDataTableMutableIndex() throws Exception {
        helpTestSelectColOnlyInDataTable(true, false);
    }

    @Test
    public void testSelectColOnlyInDataTableMutableLocalIndex() throws Exception {
        helpTestSelectColOnlyInDataTable(true, false);
    }

    protected void helpTestSelectColOnlyInDataTable(boolean mutable, boolean localIndex) throws Exception {
        String dataTableName = mutable ? MUTABLE_INDEX_DATA_TABLE : INDEX_DATA_TABLE;
        String fullDataTableName = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + dataTableName;
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            populateDataTable(conn, dataTableName);
            String ddl = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IDX ON " + fullDataTableName
                    + " (int_col1+1)";

            conn = DriverManager.getConnection(getUrl(), props);
            conn.setAutoCommit(false);
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            String sql = "SELECT int_col1+1, int_col2 FROM " + fullDataTableName + " WHERE int_col1+1=2";
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + sql);
            assertEquals("CLIENT PARALLEL 1-WAY "
                    + (localIndex ? "RANGE SCAN OVER _LOCAL_IDX_" + fullDataTableName
                            + " [-32768,2]\n    SERVER FILTER BY FIRST KEY ONLY\nCLIENT MERGE SORT" : "FULL SCAN OVER "
                            + fullDataTableName + "\n    SERVER FILTER BY (A.INT_COL1 + 1) = 2"),
                    QueryUtil.getExplainPlan(rs));
            rs = conn.createStatement().executeQuery(sql);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX IDX ON " + fullDataTableName);
        } finally {
            conn.close();
        }
    }
    
    @Test
    public void testImmutableIndexWithCaseSensitiveCols() throws Exception {
        helpTestIndexWithCaseSensitiveCols(false, false);
    }
    
    @Test
    public void testImmutableLocalIndexWithCaseSensitiveCols() throws Exception {
        helpTestIndexWithCaseSensitiveCols(true, false);
    }
    
    @Test
    public void testMutableIndexWithCaseSensitiveCols() throws Exception {
        helpTestIndexWithCaseSensitiveCols(true, false);
    }
    
    @Test
    public void testMutableLocalIndexWithCaseSensitiveCols() throws Exception {
        helpTestIndexWithCaseSensitiveCols(true, false);
    }
    
    protected void helpTestIndexWithCaseSensitiveCols(boolean mutable, boolean localIndex) throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.createStatement().execute("CREATE TABLE cs (k VARCHAR NOT NULL PRIMARY KEY, \"V1\" VARCHAR, \"v2\" VARCHAR) "+ (mutable ? "IMMUTABLE_ROWS=true" : ""));
            String query = "SELECT * FROM cs";
            ResultSet rs = conn.createStatement().executeQuery(query);
            assertFalse(rs.next());
            if (localIndex) {
                conn.createStatement().execute("CREATE LOCAL INDEX ics ON cs (\"v2\" || '_modified') INCLUDE (\"V1\",\"v2\")");
            } else {
                conn.createStatement().execute("CREATE INDEX ics ON cs (\"V1\" || '_' || \"v2\") INCLUDE (\"V1\",\"v2\")");
            }
            query = "SELECT * FROM ics";
            rs = conn.createStatement().executeQuery(query);
            assertFalse(rs.next());

            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO cs VALUES(?,?,?)");
            stmt.setString(1,"a");
            stmt.setString(2, "x");
            stmt.setString(3, "1");
            stmt.execute();
            stmt.setString(1,"b");
            stmt.setString(2, "y");
            stmt.setString(3, "2");
            stmt.execute();
            conn.commit();

            //TODO FIX THIS change this to *
            query = "SELECT (\"V1\" || '_' || \"v2\"), k, \"V1\", \"v2\"  FROM cs WHERE (\"V1\" || '_' || \"v2\") = 'x_1'";
            rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            if(localIndex){
                assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER _LOCAL_IDX_CS [-32768,'x_1']\n"
                           + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
            } else {
                assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER ICS ['x_1']", QueryUtil.getExplainPlan(rs));
            }

            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("x_1",rs.getString(1));
            assertEquals("a",rs.getString(2));
            assertEquals("x",rs.getString(3));
            assertEquals("1",rs.getString(4));
            //TODO figure out why this " " is needed
            assertEquals("x_1",rs.getString("\"('V1' || '_' || 'v2')\""));
            assertEquals("a",rs.getString("k"));
            assertEquals("x",rs.getString("V1"));
            assertEquals("1",rs.getString("v2"));
            assertFalse(rs.next());

            query = "SELECT \"V1\", \"V1\" as foo1, (\"V1\" || '_' || \"v2\") as foo, (\"V1\" || '_' || \"v2\") as \"Foo1\", (\"V1\" || '_' || \"v2\") FROM cs ORDER BY foo";
            rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            if(localIndex){
                assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER _LOCAL_IDX_CS [-32768]\nCLIENT MERGE SORT",
                    QueryUtil.getExplainPlan(rs));
            } else {
                assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER ICS", QueryUtil.getExplainPlan(rs));
            }

            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("x",rs.getString(1));
            assertEquals("x",rs.getString("V1"));
            assertEquals("x",rs.getString(2));
            assertEquals("x",rs.getString("foo1"));
            assertEquals("x_1",rs.getString(3));
            assertEquals("x_1",rs.getString("Foo"));
            assertEquals("x_1",rs.getString(4));
            assertEquals("x_1",rs.getString("Foo1"));
            assertEquals("x_1",rs.getString(5));
            assertEquals("x_1",rs.getString("\"('V1' || '_' || 'v2')\""));
            assertTrue(rs.next());
            assertEquals("y",rs.getString(1));
            assertEquals("y",rs.getString("V1"));
            assertEquals("y",rs.getString(2));
            assertEquals("y",rs.getString("foo1"));
            assertEquals("y_2",rs.getString(3));
            assertEquals("y_2",rs.getString("Foo"));
            assertEquals("y_2",rs.getString(4));
            assertEquals("y_2",rs.getString("Foo1"));
            assertEquals("y_2",rs.getString(5));
            assertEquals("y_2",rs.getString("\"('V1' || '_' || 'v2')\""));
            assertFalse(rs.next());
            conn.createStatement().execute("DROP INDEX ICS ON CS");
        } finally {
            conn.close();
        }
    }
    
    @Test
    public void testImmutableIndexDropIndexedColumn() throws Exception {
        helpTestDropIndexedColumn(false, false);
    }
    
    @Test
    public void testImmutableLocalIndexDropIndexedColumn() throws Exception {
        helpTestDropIndexedColumn(false, true);
    }
    
    @Test
    public void testMutableIndexDropIndexedColumn() throws Exception {
        helpTestDropIndexedColumn(true, false);
    }
    
    @Test
    public void testMutableLocalIndexDropIndexedColumn() throws Exception {
        helpTestDropIndexedColumn(true, true);
    }
    
    public void helpTestDropIndexedColumn(boolean mutable, boolean local) throws Exception {
        String query;
        ResultSet rs;
        PreparedStatement stmt;

        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);

        // make sure that the tables are empty, but reachable
        conn.createStatement().execute(
          "CREATE TABLE t (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)");
        query = "SELECT * FROM t" ;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
        String indexName = "it_" + (mutable ? "m" : "im") + "_" + (local ? "l" : "h");
        conn.createStatement().execute("CREATE " + ( local ? "LOCAL" : "") + " INDEX " + indexName + " ON t (v1 || '_' || v2)");

        query = "SELECT * FROM t";
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());

        // load some data into the table
        stmt = conn.prepareStatement("UPSERT INTO t VALUES(?,?,?)");
        stmt.setString(1, "a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.execute();
        conn.commit();

        assertIndexExists(conn,true);
        conn.createStatement().execute("ALTER TABLE t DROP COLUMN v1");
        assertIndexExists(conn,false);

        query = "SELECT * FROM t";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("1",rs.getString(2));
        assertFalse(rs.next());

        // load some data into the table
        stmt = conn.prepareStatement("UPSERT INTO t VALUES(?,?)");
        stmt.setString(1, "a");
        stmt.setString(2, "2");
        stmt.execute();
        conn.commit();

        query = "SELECT * FROM t";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("2",rs.getString(2));
        assertFalse(rs.next());
    }
    
    private static void assertIndexExists(Connection conn, boolean exists) throws SQLException {
        ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "T", false, false);
        assertEquals(exists, rs.next());
    }

}
