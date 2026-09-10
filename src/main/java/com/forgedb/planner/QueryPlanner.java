package com.forgedb.planner;

import com.forgedb.catalog.Column;
import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.execution.ExecutionException;
import com.forgedb.index.BTree;
import com.forgedb.sql.ast.*;

import java.util.List;

/**
 * Lightweight rule-based query planner for ForgeDB Milestone 7.
 *
 * <h2>Responsibility</h2>
 * Given a SQL statement's table name, WHERE clause, and the available
 * indexes, decide whether to use a B+ tree index lookup or a sequential
 * scan. Produce a {@link QueryPlan} that the executor will carry out.
 *
 * <h2>How the planner chooses</h2>
 * <pre>
 * 1. Is there a WHERE clause?
 *    No  → SeqScan (no filter at all)
 *
 * 2. Inspect the WHERE for a "simple predicate":
 *      &lt;indexedColumn&gt; &lt;op&gt; &lt;literal&gt;
 *    where op ∈ { =, &lt;&gt;, &lt;, &lt;=, &gt;, &gt;= }.
 *    Also handles the reverse form: &lt;literal&gt; &lt;op&gt; &lt;column&gt;
 *    (normalised: literal &lt; col becomes col &gt; literal, etc.)
 *
 * 3. AND conjunctions: if the WHERE is (A AND B) and one sub-expression
 *    is a simple predicate on the indexed column, use the index for that
 *    sub-expression and treat the other sub-expression as a residual
 *    predicate applied after the index lookup.
 *
 * 4. Is there a B+ tree index on the column named in the predicate?
 *    No  → SeqScan (fall through)
 *    Yes → IndexLookup
 *
 * 5. Priority: equality (=) is preferred first; range ops (&lt;, &gt;, etc.)
 *    are also supported for the index scan.
 *
 * 6. OR, NOT, and complex nested expressions → SeqScan (not indexable).
 *
 * 7. NEQ (&lt;&gt;) on an indexed column → SeqScan, because a range scan for
 *    NEQ would require reading almost the entire index anyway.
 * </pre>
 *
 * <h2>Thread safety</h2>
 * QueryPlanner is stateless. All methods are pure functions of their inputs.
 * It is thread-safe.
 */
public final class QueryPlanner {

    /**
     * Produces a query plan for a SELECT statement.
     *
     * @param tableName   the table to query
     * @param schema      the table schema
     * @param whereClause the WHERE predicate (null = no filter)
     * @param selectList  the SELECT projection list
     * @param index       the B+ tree index available for this table, or null
     * @return a QueryPlan (never null)
     */
    public QueryPlan planSelect(String tableName, Schema schema,
                                 Expression whereClause,
                                 List<SelectItem> selectList,
                                 BTree index) {
        if (whereClause == null || index == null) {
            return QueryPlan.seqScan(tableName, whereClause, selectList);
        }

        PredicateInfo pred = extractIndexablePredicate(whereClause, schema, index);
        if (!pred.isIndexable()) {
            return QueryPlan.seqScan(tableName, whereClause, selectList);
        }

        return QueryPlan.indexLookup(tableName, whereClause, selectList,
                                     pred.columnName(), pred.op(),
                                     pred.literalValue(), pred.residualWhere());
    }

    /**
     * Produces a query plan for a DELETE statement.
     * Same logic as SELECT, but selectList is null.
     */
    public QueryPlan planDelete(String tableName, Schema schema,
                                 Expression whereClause, BTree index) {
        return planSelect(tableName, schema, whereClause, null, index);
    }

    /**
     * Produces a query plan for an UPDATE statement.
     * Same logic as SELECT, but selectList is null.
     */
    public QueryPlan planUpdate(String tableName, Schema schema,
                                 Expression whereClause, BTree index) {
        return planSelect(tableName, schema, whereClause, null, index);
    }

    // =========================================================================
    // Predicate extraction
    // =========================================================================

    /**
     * Tries to extract a usable index predicate from the WHERE expression.
     *
     * Returns {@link PredicateInfo#notIndexable()} if no suitable predicate
     * was found.
     */
    PredicateInfo extractIndexablePredicate(Expression where,
                                             Schema schema, BTree index) {
        // Case 1: simple comparison — col op literal  or  literal op col
        if (where instanceof BinaryExpression bin) {
            BinaryExpression.Op op = bin.op();

            if (isComparisonOp(op) && op != BinaryExpression.Op.NEQ) {
                // Try: col op literal
                if (bin.left() instanceof ColumnRef ref &&
                    isLiteral(bin.right())) {
                    return tryIndexPredicate(ref.columnName(), op,
                                             bin.right(), schema, index, null);
                }
                // Try: literal op col  (flip operator)
                if (bin.right() instanceof ColumnRef ref &&
                    isLiteral(bin.left())) {
                    BinaryExpression.Op flipped = flipOp(op);
                    return tryIndexPredicate(ref.columnName(), flipped,
                                             bin.left(), schema, index, null);
                }
            }

            // Case 2: AND conjunction — try each side as the index predicate
            if (op == BinaryExpression.Op.AND) {
                // Try left side as index predicate, right as residual
                PredicateInfo left = extractIndexablePredicate(
                                         bin.left(), schema, index);
                if (left.isIndexable()) {
                    // Residual = the right side (AND the existing residual if any)
                    Expression residual = composeResidual(left.residualWhere(), bin.right());
                    return PredicateInfo.indexable(left.columnName(), left.op(),
                                                   left.literalValue(), residual);
                }
                // Try right side as index predicate, left as residual
                PredicateInfo right = extractIndexablePredicate(
                                          bin.right(), schema, index);
                if (right.isIndexable()) {
                    Expression residual = composeResidual(right.residualWhere(), bin.left());
                    return PredicateInfo.indexable(right.columnName(), right.op(),
                                                   right.literalValue(), residual);
                }
            }
        }

        return PredicateInfo.notIndexable();
    }

    /**
     * Checks whether the named column has an index, and whether the literal
     * value is type-compatible with the index key type.
     *
     * @param colName    the column referenced in the predicate
     * @param op         the comparison operator (already normalised to col-op-literal)
     * @param literalExpr the literal AST node on the right side
     * @param schema     the table schema
     * @param index      the available index (on the first column)
     * @param residual   any additional predicate to carry along
     */
    private PredicateInfo tryIndexPredicate(String colName,
                                             BinaryExpression.Op op,
                                             Expression literalExpr,
                                             Schema schema,
                                             BTree index,
                                             Expression residual) {
        // The index is built on the first column only (M6/M7 constraint)
        if (!schema.hasColumn(colName)) return PredicateInfo.notIndexable();
        Column col = schema.getColumn(colName);

        // The indexed column must be column 0
        if (schema.getColumnIndex(colName) != 0) return PredicateInfo.notIndexable();

        // The index key type must match the column type
        if (index.keyType() != col.type()) return PredicateInfo.notIndexable();

        // Evaluate the literal to a Java value
        Object literalValue = extractLiteralValue(literalExpr);
        if (literalValue == null) return PredicateInfo.notIndexable();

        // Coerce the literal value to the column's type
        try {
            literalValue = com.forgedb.execution.ExpressionEvaluator.coerce(
                               literalValue, col.type(), colName);
        } catch (ExecutionException e) {
            return PredicateInfo.notIndexable();  // type incompatible → seq scan
        }

        return PredicateInfo.indexable(colName, op, literalValue, residual);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns true if op is one of the six comparison operators. */
    private static boolean isComparisonOp(BinaryExpression.Op op) {
        return switch (op) {
            case EQ, NEQ, LT, LTE, GT, GTE -> true;
            default -> false;
        };
    }

    /** Returns true if the expression is a pure literal (no column refs). */
    private static boolean isLiteral(Expression expr) {
        return expr instanceof IntLiteral
            || expr instanceof LongLiteral
            || expr instanceof DoubleLiteral
            || expr instanceof StringLiteral
            || expr instanceof BoolLiteral
            || expr instanceof NullLiteral;
    }

    /**
     * Extracts a Java boxed value from a literal AST node.
     * Returns null for NullLiteral (cannot be used as a BTree key).
     */
    private static Object extractLiteralValue(Expression expr) {
        if (expr instanceof IntLiteral    e) return e.value();
        if (expr instanceof LongLiteral   e) return e.value();
        if (expr instanceof DoubleLiteral e) return e.value();
        if (expr instanceof StringLiteral e) return e.value();
        if (expr instanceof BoolLiteral   e) return e.value();
        return null;  // NullLiteral or non-literal
    }

    /**
     * Flips a comparison operator for the case where the literal is on the
     * left side: {@code 5 < col} is normalised to {@code col > 5}.
     *
     * <pre>
     *   EQ  ↔ EQ    (symmetric)
     *   NEQ ↔ NEQ   (symmetric)
     *   LT  ↔ GT
     *   LTE ↔ GTE
     *   GT  ↔ LT
     *   GTE ↔ LTE
     * </pre>
     */
    private static BinaryExpression.Op flipOp(BinaryExpression.Op op) {
        return switch (op) {
            case LT  -> BinaryExpression.Op.GT;
            case LTE -> BinaryExpression.Op.GTE;
            case GT  -> BinaryExpression.Op.LT;
            case GTE -> BinaryExpression.Op.LTE;
            default  -> op;   // EQ, NEQ are symmetric
        };
    }

    /**
     * Composes a residual predicate. If both sides are non-null, wraps them
     * in an AND expression. If one is null, returns the other.
     */
    private static Expression composeResidual(Expression existing, Expression extra) {
        if (existing == null) return extra;
        if (extra    == null) return existing;
        return new BinaryExpression(existing, BinaryExpression.Op.AND, extra);
    }
}
