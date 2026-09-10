package com.forgedb.planner;

import com.forgedb.sql.ast.Expression;
import com.forgedb.sql.ast.SelectItem;

import java.util.List;

/**
 * An immutable description of how a query will be executed.
 *
 * The planner produces a QueryPlan; the executor reads it and executes the
 * chosen strategy. Separating planning from execution lets us:
 *   - Log/display the chosen plan (EXPLAIN)
 *   - Test plan selection independently of execution
 *   - Add cost-based optimisation later without changing the executor
 *
 * Plan types (see {@link PlanType}):
 *   SEQ_SCAN     — read every live row from the heap file
 *   INDEX_LOOKUP — use a B+ tree to find RecordIds, then fetch tuples
 *
 * Every plan carries the original WHERE clause so the executor can apply
 * any residual predicates after the initial scan (e.g., an index lookup
 * on {@code id = 5} with an additional {@code AND name = 'Alice'} residual).
 */
public final class QueryPlan {

    /** The two plan types supported in Milestone 7. */
    public enum PlanType {
        /** Full sequential scan of the heap file. */
        SEQ_SCAN,
        /**
         * B+ tree index lookup: use the index to locate RecordIds matching
         * the index predicate, then fetch each tuple from the heap by RecordId,
         * then apply any residual predicates.
         */
        INDEX_LOOKUP
    }

    // -------------------------------------------------------------------------
    // Fields (applicable to all plan types unless noted)
    // -------------------------------------------------------------------------

    /** The plan type chosen by the planner. */
    private final PlanType type;

    /** The table this plan reads from. */
    private final String tableName;

    /**
     * The full WHERE clause (before any index decomposition).
     * May be null (no filter).
     */
    private final Expression whereClause;

    /**
     * The columns to project (SELECT list).
     * May be null / empty for DELETE/UPDATE plans.
     */
    private final List<SelectItem> selectList;

    // -------------------------------------------------------------------------
    // INDEX_LOOKUP specific fields
    // -------------------------------------------------------------------------

    /**
     * The column name the index is built on.
     * Null for SEQ_SCAN.
     */
    private final String indexedColumn;

    /**
     * The comparison operator used in the index predicate
     * (e.g., {@code BinaryExpression.Op.EQ}).
     * Null for SEQ_SCAN.
     */
    private final com.forgedb.sql.ast.BinaryExpression.Op indexOp;

    /**
     * The literal key value used in the index lookup (already coerced to the
     * column's DataType as a Java boxed type: Integer, Long, Double, String).
     * Null for SEQ_SCAN.
     */
    private final Object indexKeyValue;

    /**
     * Any residual WHERE predicate that cannot be satisfied by the index alone
     * (e.g., additional AND conditions). Null means no residual.
     */
    private final Expression residualWhere;

    // -------------------------------------------------------------------------
    // Private constructor — use factories
    // -------------------------------------------------------------------------

    private QueryPlan(PlanType type, String tableName, Expression whereClause,
                      List<SelectItem> selectList,
                      String indexedColumn,
                      com.forgedb.sql.ast.BinaryExpression.Op indexOp,
                      Object indexKeyValue, Expression residualWhere) {
        this.type          = type;
        this.tableName     = tableName;
        this.whereClause   = whereClause;
        this.selectList    = selectList;
        this.indexedColumn = indexedColumn;
        this.indexOp       = indexOp;
        this.indexKeyValue = indexKeyValue;
        this.residualWhere = residualWhere;
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Creates a sequential scan plan.
     *
     * @param tableName   the table to scan
     * @param whereClause the full WHERE predicate (null = no filter)
     * @param selectList  the projection list (null for DELETE/UPDATE)
     */
    public static QueryPlan seqScan(String tableName, Expression whereClause,
                                     List<SelectItem> selectList) {
        return new QueryPlan(PlanType.SEQ_SCAN, tableName, whereClause,
                             selectList, null, null, null, null);
    }

    /**
     * Creates an index lookup plan.
     *
     * @param tableName      the table to read from
     * @param whereClause    the full original WHERE predicate
     * @param selectList     the projection list (null for DELETE/UPDATE)
     * @param indexedColumn  the column the B+ tree is built on
     * @param indexOp        the comparison operator used for the index predicate
     * @param indexKeyValue  the literal key value (boxed Java type)
     * @param residualWhere  any additional predicate to apply after index lookup
     *                       (null = no residual)
     */
    public static QueryPlan indexLookup(String tableName, Expression whereClause,
                                         List<SelectItem> selectList,
                                         String indexedColumn,
                                         com.forgedb.sql.ast.BinaryExpression.Op indexOp,
                                         Object indexKeyValue,
                                         Expression residualWhere) {
        return new QueryPlan(PlanType.INDEX_LOOKUP, tableName, whereClause,
                             selectList, indexedColumn, indexOp, indexKeyValue,
                             residualWhere);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public PlanType    type()          { return type; }
    public String      tableName()     { return tableName; }
    public Expression  whereClause()   { return whereClause; }
    public List<SelectItem> selectList() { return selectList; }
    public String      indexedColumn() { return indexedColumn; }
    public com.forgedb.sql.ast.BinaryExpression.Op indexOp() { return indexOp; }
    public Object      indexKeyValue() { return indexKeyValue; }
    public Expression  residualWhere() { return residualWhere; }

    // -------------------------------------------------------------------------
    // EXPLAIN output
    // -------------------------------------------------------------------------

    /**
     * Returns a human-readable representation of this plan, similar to
     * PostgreSQL's EXPLAIN output.
     *
     * Example outputs:
     * <pre>
     *   SeqScan [ table=users, filter=(id = 42) ]
     *   IndexLookup [ table=users, index=id, op=EQ, key=42, residual=null ]
     * </pre>
     */
    public String explain() {
        return switch (type) {
            case SEQ_SCAN ->
                "SeqScan [ table=" + tableName +
                ", filter=" + (whereClause == null ? "none" : whereClause) + " ]";
            case INDEX_LOOKUP ->
                "IndexLookup [ table=" + tableName +
                ", index=" + indexedColumn +
                ", op=" + indexOp +
                ", key=" + indexKeyValue +
                ", residual=" + (residualWhere == null ? "none" : residualWhere) + " ]";
        };
    }

    @Override
    public String toString() {
        return explain();
    }
}
