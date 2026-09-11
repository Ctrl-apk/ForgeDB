package com.forgedb.sql.ast;

/**
 * Visitor interface for executing or analysing SQL statement AST nodes.
 *
 * The query execution engine (Milestone 6) will implement this interface to
 * dispatch each statement type to the correct execution logic without
 * instanceof chains.
 *
 * Using a generic return type T allows the visitor to return query results,
 * status messages, row counts, etc., depending on context.
 *
 * @param <T> the return type of each visit method
 */
public interface StatementVisitor<T> {
    T visitCreateTable(CreateTableStatement stmt) throws Exception;
    T visitInsert(InsertStatement stmt)           throws Exception;
    T visitSelect(SelectStatement stmt)           throws Exception;
    T visitDelete(DeleteStatement stmt)           throws Exception;
    T visitUpdate(UpdateStatement stmt)           throws Exception;
    T visitBegin   (BeginStatement    stmt)       throws Exception;
    T visitCommit  (CommitStatement   stmt)       throws Exception;
    T visitRollback(RollbackStatement stmt)       throws Exception;
}
