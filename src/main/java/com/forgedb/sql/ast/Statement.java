package com.forgedb.sql.ast;

/**
 * Marker interface for all top-level SQL statement AST nodes.
 *
 * Every concrete statement node implements this interface so the query
 * engine (Milestone 6) can use a single type for the result of parsing.
 *
 * Visitor pattern support: each implementation accepts a StatementVisitor,
 * which lets the execution engine dispatch without instanceof chains.
 */
public interface Statement {
    /** Accept a visitor. Used by the query execution engine (Milestone 6). */
    <T> T accept(StatementVisitor<T> visitor) throws Exception;
}
