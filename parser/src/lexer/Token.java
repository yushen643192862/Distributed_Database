package lexer;

public class Token {
    public tokenType type;
    public Object value;

    public Token(tokenType type) {
        this.type = type;
    }
    public Token(tokenType type, Object value) {
        this.type = type;
        this.value = value;
    }
}
