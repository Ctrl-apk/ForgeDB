package com.forgedb.sql.ast;

/**
 * Visitor interface for evaluating or analysing SQL expression AST nodes.
 *
 * Milestone 6 will implement this to evaluate WHERE conditions and
 * compute literal values for INSERT/UPDATE.
 *
 * @param <T> the return type of each visit method (e.g. Object for runtime
 *            evaluation, Boolean for filter predicates)
 */
public interface ExpressionVisitor<T> {
    T visitIntLiteral    (IntLiteral     expr) throws Exception;
    T visitLongLiteral   (LongLiteral    expr) throws Exception;
    T visitDoubleLiteral (DoubleLiteral  expr) throws Exception;
    T visitStringLiteral (StringLiteral  expr) throws Exception;
    T visitBoolLiteral   (BoolLiteral    expr) throws Exception;
    T visitNullLiteral   (NullLiteral    expr) throws Exception;
    T visitColumnRef     (ColumnRef      expr) throws Exception;
    T visitBinaryExpr    (BinaryExpression expr) throws Exception;
    T visitUnaryExpr     (UnaryExpression  expr) throws Exception;
}
