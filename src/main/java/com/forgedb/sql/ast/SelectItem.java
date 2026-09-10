package com.forgedb.sql.ast;

/**
 * A single item in a SELECT list.
 *
 * Either a wildcard ({@code *}) or a named column/expression with an optional
 * alias ({@code expr AS alias}).
 *
 * {@code isStar} is true when the item is the SQL wildcard.
 * {@code alias}  is null when no AS clause was specified.
 */
public final class SelectItem {
    private final boolean    isStar;
    private final Expression expr;    // null when isStar
    private final String     alias;   // nullable

    /** Factory for the wildcard {@code *} item. */
    public static SelectItem star() {
        return new SelectItem(true, null, null);
    }

    /** Factory for a column or expression item. */
    public static SelectItem of(Expression expr, String alias) {
        return new SelectItem(false, expr, alias);
    }

    private SelectItem(boolean isStar, Expression expr, String alias) {
        this.isStar = isStar;
        this.expr   = expr;
        this.alias  = alias;
    }

    public boolean    isStar() { return isStar; }
    public Expression expr()   { return expr; }
    public String     alias()  { return alias; }

    @Override public String toString() {
        if (isStar) return "*";
        return alias == null ? String.valueOf(expr) : expr + " AS " + alias;
    }
}
