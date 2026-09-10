package com.forgedb.sql.ast;

/**
 * Base interface for all expression AST nodes.
 *
 * Expressions appear in WHERE clauses, VALUES lists, and SET clauses.
 * They form a recursive tree: a BinaryExpression holds two child Expressions,
 * leaf nodes are literals (IntLiteral, StringLiteral, etc.) or column
 * references (ColumnRef).
 *
 * The expression evaluator (Milestone 6) will walk this tree to compute
 * values at runtime.
 */
public interface Expression {
    /** Accept a visitor for evaluation or analysis. */
    <T> T accept(ExpressionVisitor<T> visitor) throws Exception;
}
