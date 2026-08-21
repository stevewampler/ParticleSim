package particlesim.expr

/** Thrown for any lexical, syntactic, or type error in an expression string — always at parse
 * time (§4.1: "the moment the simulation is loaded, not four thousand steps into a run"). */
class ExpressionException(message: String) : RuntimeException(message)

enum class TokenType { NUMBER, IDENT, PLUS, MINUS, STAR, SLASH, CARET, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, EOF }

data class Token(val type: TokenType, val text: String, val position: Int)

/** Turns an expression source string into a flat token stream (§4.1's grammar: arithmetic,
 * identifiers/function names, numbers, vector-literal brackets). No expression-language
 * keywords are reserved beyond what the parser resolves semantically (`t`, function names) —
 * the lexer itself only knows syntax. */
object Lexer {
    fun tokenize(source: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                    val start = i
                    while (i < source.length && source[i].isDigit()) i++
                    if (i < source.length && source[i] == '.') {
                        i++
                        while (i < source.length && source[i].isDigit()) i++
                    }
                    if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                        val expStart = i
                        i++
                        if (i < source.length && (source[i] == '+' || source[i] == '-')) i++
                        if (i < source.length && source[i].isDigit()) {
                            while (i < source.length && source[i].isDigit()) i++
                        } else {
                            i = expStart // not actually an exponent (e.g. "2e" with no digits) — leave it for IDENT/error
                        }
                    }
                    tokens += Token(TokenType.NUMBER, source.substring(start, i), start)
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    tokens += Token(TokenType.IDENT, source.substring(start, i), start)
                }
                c == '+' -> { tokens += Token(TokenType.PLUS, "+", i); i++ }
                c == '-' -> { tokens += Token(TokenType.MINUS, "-", i); i++ }
                c == '*' -> { tokens += Token(TokenType.STAR, "*", i); i++ }
                c == '/' -> { tokens += Token(TokenType.SLASH, "/", i); i++ }
                c == '^' -> { tokens += Token(TokenType.CARET, "^", i); i++ }
                c == '(' -> { tokens += Token(TokenType.LPAREN, "(", i); i++ }
                c == ')' -> { tokens += Token(TokenType.RPAREN, ")", i); i++ }
                c == '[' -> { tokens += Token(TokenType.LBRACKET, "[", i); i++ }
                c == ']' -> { tokens += Token(TokenType.RBRACKET, "]", i); i++ }
                c == ',' -> { tokens += Token(TokenType.COMMA, ",", i); i++ }
                else -> throw ExpressionException("unexpected character '$c' at position $i in \"$source\"")
            }
        }
        tokens += Token(TokenType.EOF, "", source.length)
        return tokens
    }
}
