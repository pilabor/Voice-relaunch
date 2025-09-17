package voice.core.search

import voice.core.search.QueryTokenType.*

/**
 * FTS4 Notes for sqlite (source: https://saraswatmks.github.io/2020/04/sqlite-fts-search-queries.html)
 * - Wildcards: *, example: god*
 * - Operators: AND, OR, NOT, example: god AND father NOT jesus
 * - Fieldsearch: <fieldname> : <value>, example: title : god AND father
 * - Highlight: Highlight the results, example: select highlight(imdb, 0, '[', ']') from imdb where imdb MATCH "{q}"

 More examples:
(title : the OR of) AND (genre: Action OR Comedy)
 */

enum class QueryTokenType {
  Whitespace,
  Text,
  LeftParenthesis,
  RightParenthesis,
  LeftSquareBracket,
  RightSquareBracket,
  LeftCurlyBracket,
  RightCurlyBracket,
  DoubleQuote,
  Asterisk,
  QuestionMark,
  Escape,
  Plus,
  Minus,
  Colon,
}

 data class QueryToken(val type: QueryTokenType, val value: String?=null)

class TokenizerString(private val string: String) {
  private val chars = string.toCharArray()
  private var currentIndex = 0

  fun peek(): Char? {
    if(hasMoreChars()) {
      return chars[currentIndex]
    }
    return null
  }
  fun poke(): Char? {
    val char = peek()
    if(char != null) {
      currentIndex++
    }
    return char
  }

  fun hasMoreChars(): Boolean {
    return chars.size > currentIndex
  }
}


class QueryTokenizer {
  fun tokenize(query: String): List<QueryToken> {
    val tokens = mutableListOf<QueryToken>()
    val it = TokenizerString(query)
    val buffer = StringBuffer()
    while(it.hasMoreChars()) {
      val currentChar = it.poke()
      val currentTokenType = when (currentChar) {
          '+' -> Plus
          '-' -> Minus
          ':' -> Colon
          '(' -> LeftParenthesis
          ')' -> RightParenthesis
          '[' -> LeftSquareBracket
          ']' -> RightSquareBracket
          '{' -> LeftCurlyBracket
          '}' -> RightCurlyBracket
          '"' -> DoubleQuote
          '*' -> Asterisk
          '?' -> QuestionMark
          '\\' -> Escape
          else -> {
            if(currentChar?.isWhitespace() == true) {
              Whitespace
            } else {
              Text
            }
          }
      }

      when(currentTokenType) {
        Whitespace -> tokens.addAll(flushTokenBuffer(buffer, QueryToken(Whitespace,eatAllWhitespace(currentChar, it))))
        Text -> buffer.append(currentChar)
        else -> tokens.addAll(flushTokenBuffer(buffer, QueryToken(currentTokenType)))
      }
    }
    tokens.addAll(flushTokenBuffer(buffer))
    return tokens
  }

  private fun flushTokenBuffer(buffer: StringBuffer, extraToken: QueryToken?=null): List<QueryToken> {
    val tokens = mutableListOf<QueryToken>()
    if(buffer.isNotEmpty()) {
      tokens.add(QueryToken(Text, buffer.toString()))
      buffer.setLength(0)
    }
    if(extraToken != null) {
      tokens.add(extraToken)
    }
    return tokens
  }

  private fun eatAllWhitespace(currentChar: Char?, it: TokenizerString): String {
    val buffer = StringBuffer()
    if(currentChar != null) {
      buffer.append(currentChar)
    }
    while(it.hasMoreChars() && it.peek()?.isWhitespace() == true) {
      buffer.append(it.poke())
    }
    return buffer.toString()
  }
}
