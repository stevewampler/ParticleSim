package particlesim.expr

/**
 * Recursive-descent parser for §4.1's expression grammar. Precedence, loosest to tightest:
 * `+`/`-` (left-assoc) < `*`/`/` (left-assoc) < unary `-` < `^` (right-assoc). Unary minus
 * binding *looser* than `^` (not tighter) is what makes `-2^2` parse as `-(2^2) = -4`, not
 * `(-2)^2 = 4` — the conventional math-notation reading, and the one most scripting/expression
 * languages use. `^`'s right operand is parsed via [unary] (not [power]) so it can itself carry
 * a leading `-` (`2^-2`) while still recursing right-associatively for chains (`2^3^2 = 2^(3^2)
 * = 512`, not `(2^3)^2 = 64`).
 *
 * Every node's [ExprNode.type] is resolved as it's constructed (see [Ast.kt]), so a
 * scalar/vector mismatch throws [ExpressionException] during this call, not on first
 * evaluation — the parse-time type-checking §4.1 requires.
 */
class Parser private constructor(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos].also { pos++ }
    private fun check(type: TokenType): Boolean = peek().type == type
    private fun expect(type: TokenType, what: String): Token {
        if (!check(type)) throw ExpressionException("expected $what at position ${peek().position}, found '${peek().text}'")
        return advance()
    }

    private fun parseExpression(): ExprNode {
        var node = parseTerm()
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val op = if (advance().type == TokenType.PLUS) BinaryOp.ADD else BinaryOp.SUB
            node = BinaryOpNode(op, node, parseTerm())
        }
        return node
    }

    private fun parseTerm(): ExprNode {
        var node = parseUnary()
        while (check(TokenType.STAR) || check(TokenType.SLASH)) {
            val op = if (advance().type == TokenType.STAR) BinaryOp.MUL else BinaryOp.DIV
            node = BinaryOpNode(op, node, parseUnary())
        }
        return node
    }

    private fun parseUnary(): ExprNode {
        if (check(TokenType.MINUS)) {
            advance()
            return UnaryMinusNode(parseUnary())
        }
        return parsePower()
    }

    private fun parsePower(): ExprNode {
        val base = parsePrimary()
        if (check(TokenType.CARET)) {
            advance()
            return BinaryOpNode(BinaryOp.POW, base, parseUnary())
        }
        return base
    }

    private fun parsePrimary(): ExprNode {
        val token = peek()
        return when (token.type) {
            TokenType.NUMBER -> {
                advance()
                NumberLiteral(token.text.toDouble())
            }
            TokenType.IDENT -> {
                advance()
                if (check(TokenType.LPAREN)) {
                    parseFunctionCall(token.text)
                } else {
                    parseVariable(token.text, token.position)
                }
            }
            TokenType.LPAREN -> {
                advance()
                val inner = parseExpression()
                expect(TokenType.RPAREN, "')'")
                inner
            }
            TokenType.LBRACKET -> parseVectorLiteral()
            else -> throw ExpressionException("unexpected token '${token.text}' at position ${token.position}")
        }
    }

    private fun parseVariable(name: String, position: Int): ExprNode = when (name) {
        "t" -> TimeVariable(name)
        "dt" -> throw ExpressionException(
            "'dt' is not available in expressions yet — only 't' is currently wired through " +
                "(position ${position})",
        )
        else -> throw ExpressionException("unknown identifier '$name' at position $position")
    }

    private fun parseFunctionCall(name: String): ExprNode {
        expect(TokenType.LPAREN, "'('")
        val args = ArrayList<ExprNode>()
        if (!check(TokenType.RPAREN)) {
            args += parseExpression()
            while (check(TokenType.COMMA)) {
                advance()
                args += parseExpression()
            }
        }
        expect(TokenType.RPAREN, "')'")
        return FunctionCallNode(name, args)
    }

    private fun parseVectorLiteral(): ExprNode {
        expect(TokenType.LBRACKET, "'['")
        val x = parseExpression()
        expect(TokenType.COMMA, "','")
        val y = parseExpression()
        expect(TokenType.COMMA, "','")
        val z = parseExpression()
        expect(TokenType.RBRACKET, "']'")
        return VectorLiteral(x, y, z)
    }

    companion object {
        /** Parses [source] into a fully type-checked AST. */
        fun parse(source: String): ExprNode {
            val parser = Parser(Lexer.tokenize(source))
            val node = parser.parseExpression()
            if (!parser.check(TokenType.EOF)) {
                throw ExpressionException("unexpected trailing input '${parser.peek().text}' at position ${parser.peek().position}")
            }
            return node
        }
    }
}
