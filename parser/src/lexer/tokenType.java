package lexer;

public enum tokenType {
    // SQL 关键字
    SELECT, INSERT, UPDATE, DELETE,
    CREATE, DROP, ALTER, TRUNCATE, TABLE, FROM, WHERE,
    SET, ADD, COLUMN, IF, EXISTS,
    PRIMARY, KEY, FOREIGN, REFERENCES,
    NULL, UNIQUE, CHECK, DEFAULT,
    IS, BETWEEN, IN,
    AS, INNER, LEFT, RIGHT, FULL, CROSS,
    AND, OR, NOT,                    // 逻辑运算符
    ORDER, BY, LIMIT, OFFSET,        // ORDER BY, LIMIT, OFFSET 子句
    VALUES, INTO,                    // VALUES, INTO 子句
    JOIN, ON, GROUP, HAVING,         // JOIN, GROUP BY, HAVING 子句
    ASC, DESC,                        // ASC, DESC 排序

    // 比较操作符
    EQ, GT, LT, GE, LE, NE,          // =, >, <, >=, <=, <>

    // 算术操作符
    PLUS, SUB, STAR, DIVIDE, MOD,  // +, -, *, /, %

    // 分隔符
    COMMA, LPAREN, RPAREN, DOT, SEMICOLON,  // ,, (, ), ., ;

    // 值类型
    VALUE, IDENTIFIER, NUMBER, STRING,

    // 结束
    EOF
}
