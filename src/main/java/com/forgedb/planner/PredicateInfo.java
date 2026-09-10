package com.forgedb.planner;

import com.forgedb.sql.ast.BinaryExpression;
import com.forgedb.sql.ast.Expression;

/**
 * The result of analysing a WHERE predicate for index use.
 *
 * The planner inspects a WHERE expression and tries to extract a
 * "simple predicate" of the form:
 *
 *   &lt;column_name&gt; &lt;op&gt; &lt;literal&gt;
 * or
 *   &lt;literal&gt; &lt;op&gt; &lt;column_name&gt;   (normalised to column-op-literal form)
 *
 * If such a predicate is found, {@link #isIndexable()} returns true and the
 * indexed column name, operator, and literal value are accessible.
 *
 * If the WHERE clause is a conjunction (AND), the planner extracts one
 * indexable sub-predicate and records the remaining expression as the
 * residual, which the executor applies after the index lookup.
 *
 * Examples:
 * <pre>
 *   WHERE id = 42            → indexable(id, EQ, 42),   residual=null
 *   WHERE id = 42 AND age > 20 → indexable(id, EQ, 42), residual=(age > 20)
 *   WHERE age > 20 AND id = 5  → indexable(id, EQ, 5),  residual=(age > 20)
 *   WHERE name = 'Alice'     → indexable(name, EQ, "Alice"), residual=null
 *   WHERE id > 10            → indexable(id, GT, 10),   residual=null
 *   WHERE age > 20           → not indexable (no index on age)
 *   WHERE id = 1 OR id = 2   → not indexable (OR not supported for index)
 * </pre>
 *
 * This class is package-private; only the {@link QueryPlanner} uses it.
 */
final class PredicateInfo {

    private final boolean              indexable;
    private final String               columnName;   // null if !indexable
    private final BinaryExpression.Op  op;           // null if !indexable
    private final Object               literalValue; // null if !indexable
    private final Expression           residualWhere;// null if no residual

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /** Creates a result indicating no usable index predicate was found. */
    static PredicateInfo notIndexable() {
        return new PredicateInfo(false, null, null, null, null);
    }

    /**
     * Creates a result for a successfully extracted index predicate.
     *
     * @param columnName   the column the predicate is on
     * @param op           the comparison operator
     * @param literalValue the literal value (boxed Java type)
     * @param residual     any residual predicate (null if none)
     */
    static PredicateInfo indexable(String columnName, BinaryExpression.Op op,
                                    Object literalValue, Expression residual) {
        return new PredicateInfo(true, columnName, op, literalValue, residual);
    }

    private PredicateInfo(boolean indexable, String columnName,
                           BinaryExpression.Op op, Object literalValue,
                           Expression residualWhere) {
        this.indexable     = indexable;
        this.columnName    = columnName;
        this.op            = op;
        this.literalValue  = literalValue;
        this.residualWhere = residualWhere;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    boolean              isIndexable()    { return indexable; }
    String               columnName()     { return columnName; }
    BinaryExpression.Op  op()             { return op; }
    Object               literalValue()   { return literalValue; }
    Expression           residualWhere()  { return residualWhere; }
}
