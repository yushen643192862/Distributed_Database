package parser.lexer;

public enum tokenType {
    // SQL keywords
    SELECT, INSERT, UPDATE, DELETE,
    CREATE, DROP, ALTER, TRUNCATE, TABLE, FROM, WHERE,
    SET, ADD, COLUMN, IF, EXISTS,
    PRIMARY, KEY, FOREIGN, REFERENCES,
    NULL, UNIQUE, CHECK, DEFAULT,
    IS, BETWEEN, IN,
    AS, INNER, LEFT, RIGHT, FULL, CROSS,
    AND, OR, NOT,                    // logical operators
    ORDER, BY, LIMIT, OFFSET,        // ORDER BY, LIMIT, OFFSET clauses
    VALUES, INTO,                    // VALUES, INTO clauses
    JOIN, ON, GROUP, HAVING,         // JOIN, GROUP BY, HAVING clauses
    ASC, DESC,                       // ASC, DESC ordering

    // comparison operators
    EQ, GT, LT, GE, LE, NE,          // =, >, <, >=, <=, <>

    // arithmetic operators
    PLUS, SUB, STAR, DIVIDE, MOD,    // +, -, *, /, %

    // separators
    COMMA, LPAREN, RPAREN, DOT, SEMICOLON,  // ,, (, ), ., ;

    // values
    VALUE, IDENTIFIER, NUMBER, STRING,

    // end
    EOF
}
