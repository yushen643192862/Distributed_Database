package parser.lexer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Lexer {
    private String SQL;
    private int position = 0;
    private char ch;

    public  Lexer(String SQL){
        this.SQL = SQL;
        if(!Objects.equals(SQL, ""))
            ch = SQL.charAt(position);
        else
            ch = '\0';
    }
    private static final Map<String, tokenType> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("select", tokenType.SELECT);
        KEYWORDS.put("insert", tokenType.INSERT);
        KEYWORDS.put("update", tokenType.UPDATE);
        KEYWORDS.put("delete", tokenType.DELETE);
        KEYWORDS.put("create", tokenType.CREATE);
        KEYWORDS.put("drop", tokenType.DROP);
        KEYWORDS.put("alter", tokenType.ALTER);
        KEYWORDS.put("truncate", tokenType.TRUNCATE);
        KEYWORDS.put("table", tokenType.TABLE);
        KEYWORDS.put("from", tokenType.FROM);
        KEYWORDS.put("where", tokenType.WHERE);
        KEYWORDS.put("set", tokenType.SET);
        KEYWORDS.put("add", tokenType.ADD);
        KEYWORDS.put("column", tokenType.COLUMN);
        KEYWORDS.put("if", tokenType.IF);
        KEYWORDS.put("exists", tokenType.EXISTS);
        KEYWORDS.put("primary", tokenType.PRIMARY);
        KEYWORDS.put("key", tokenType.KEY);
        KEYWORDS.put("foreign", tokenType.FOREIGN);
        KEYWORDS.put("references", tokenType.REFERENCES);
        KEYWORDS.put("null", tokenType.NULL);
        KEYWORDS.put("unique", tokenType.UNIQUE);
        KEYWORDS.put("check", tokenType.CHECK);
        KEYWORDS.put("default", tokenType.DEFAULT);
        KEYWORDS.put("is", tokenType.IS);
        KEYWORDS.put("between", tokenType.BETWEEN);
        KEYWORDS.put("in", tokenType.IN);
        KEYWORDS.put("as", tokenType.AS);
        KEYWORDS.put("inner", tokenType.INNER);
        KEYWORDS.put("left", tokenType.LEFT);
        KEYWORDS.put("right", tokenType.RIGHT);
        KEYWORDS.put("full", tokenType.FULL);
        KEYWORDS.put("cross", tokenType.CROSS);
        KEYWORDS.put("and", tokenType.AND);
        KEYWORDS.put("or", tokenType.OR);
        KEYWORDS.put("not", tokenType.NOT);
        KEYWORDS.put("order", tokenType.ORDER);
        KEYWORDS.put("by", tokenType.BY);
        KEYWORDS.put("limit", tokenType.LIMIT);
        KEYWORDS.put("offset", tokenType.OFFSET);
        KEYWORDS.put("values", tokenType.VALUES);
        KEYWORDS.put("into", tokenType.INTO);
        KEYWORDS.put("join", tokenType.JOIN);
        KEYWORDS.put("on", tokenType.ON);
        KEYWORDS.put("group", tokenType.GROUP);
        KEYWORDS.put("having", tokenType.HAVING);
        KEYWORDS.put("asc", tokenType.ASC);
        KEYWORDS.put("desc", tokenType.DESC);
    }
    private void advance(){
        position++;
        if(position == SQL.length()){
            ch = '\0';
        }
        else{
            ch = SQL.charAt(position);
        }
    }
    private void skipWhitespace(){
        while (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
            advance();
        }
    }
    private tokenType symbolToken(){
        if(ch == '='){
            advance();
            return tokenType.EQ;
        }
        else if(ch == '<'){
            advance();
            if(ch == '='){
                advance();
                return tokenType.LE;
            }
            if(ch == '>'){
                advance();
                return tokenType.NE;
            }
            return tokenType.LT;
        }
        else if(ch == '>'){
            advance();
            if(ch == '='){
                advance();
                return tokenType.GE;
            }
            return tokenType.GT;
        }
        else if(ch == '*'){
            advance();
            return tokenType.STAR;
        }
        else if(ch == ','){
            advance();
            return tokenType.COMMA;
        }
        else if(ch == '('){
            advance();
            return tokenType.LPAREN;
        }
        else if(ch == ')'){
            advance();
            return tokenType.RPAREN;
        }
        else if(ch == ';'){
            advance();
            return tokenType.SEMICOLON;
        }
        else if(ch == '+'){
            advance();
            return tokenType.PLUS;
        }
        else if(ch == '-'){
            advance();
            return tokenType.SUB;
        }
        else  if(ch == '/'){
            advance();
            return tokenType.DIVIDE;
        }
        else if(ch == '%'){
            advance();
            return tokenType.MOD;
        }

        throw new RuntimeException("Unknown character: " + ch + " at position " + position);
    }

    private String readWord() {
        StringBuilder sb = new StringBuilder();
        boolean isfirst = true;
        while (ch != '\0' && Character.isLetter(ch) || ch == '_' || (Character.isDigit(ch) && !isfirst)) {
            isfirst = false;
            sb.append(ch);
            advance();
        }
        return sb.toString().toLowerCase();
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        advance();
        while (!(ch == '\'')) {
            if( ch == '\0'){
                throw new RuntimeException("Unterminated string");
            }
            sb.append(ch);
            advance();
        }
        advance();
        return sb.toString();
    }

    private String readNumber(Token token) {
        StringBuilder sb = new StringBuilder();
        int DOT_count = 0;
        char lastChar = '\0';
        if(ch == '.'){
            throw new RuntimeException("Unterminated number");
        }
        while ((Character.isDigit(ch) || ch == '.')) {
            if( ch == '.'){
                DOT_count++;
            }
            sb.append(ch);
            lastChar = ch;
            advance();
        }
        if(lastChar == '.'){
            throw new RuntimeException("Unterminated number");
        }
        if(DOT_count == 0){
            token.value = Integer.valueOf(sb.toString());
        }
        else if(DOT_count == 1){
            token.value = Float.valueOf(sb.toString());
        }
        else if(DOT_count > 1 ){
            throw new RuntimeException("Invalid number: multiple decimal points");
        }
        return sb.toString();
    }

    public Token nextToken(){
        skipWhitespace();
        if (ch == '\0') {
            return new Token(tokenType.EOF);
        }
        if (Character.isLetter(ch)) {
            String word = readWord();
            tokenType type = KEYWORDS.getOrDefault(word, tokenType.IDENTIFIER);
            return new Token(type, word);
        }

        if (Character.isDigit(ch)) {
            Token token = new Token(tokenType.NUMBER);
            readNumber(token);
            return token;
        }

        if (ch == '.') {
            advance();
            return new Token(tokenType.DOT, ".");
        }

        if (ch == '\'' || ch == '"') {
            String str = readString();
            return new Token(tokenType.STRING, str);
        }
        tokenType type = symbolToken();
        return new Token(type);
    }
}
