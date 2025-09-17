package voice.core.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.collections.shouldBeSameSizeAs
import org.junit.Test
import org.junit.runner.RunWith
import io.kotest.matchers.shouldBe

@RunWith(AndroidJUnit4::class)
class QueryTokenizerTest {
  @Test
  fun tokenize() {
    val tokenizer = QueryTokenizer()



    assertTokens(
      tokenizer.tokenize("hello world"),
      listOf(
        token(QueryTokenType.Text, "hello"),
        token(QueryTokenType.Whitespace, " "),
        token(QueryTokenType.Text, "world"),
        ),
    )

    assertTokens(
      tokenizer.tokenize("(hello  +world* title:\"a-title\") date:[20241201 TO 20250101]"),
      listOf(
        token(QueryTokenType.LeftParenthesis),
        token(QueryTokenType.Text, "hello"),
        token(QueryTokenType.Whitespace, "  "),
        token(QueryTokenType.Plus),
        token(QueryTokenType.Text, "world"),
        token(QueryTokenType.Asterisk),
        token(QueryTokenType.Whitespace, " "),
        token(QueryTokenType.Text, "title"),
        token(QueryTokenType.Colon),
        token(QueryTokenType.DoubleQuote),
        token(QueryTokenType.Text, "a"),
        token(QueryTokenType.Minus),
        token(QueryTokenType.Text, "title"),
        token(QueryTokenType.DoubleQuote),
        token(QueryTokenType.RightParenthesis),
        token(QueryTokenType.Whitespace, " "),
        token(QueryTokenType.Text, "date"),
        token(QueryTokenType.Colon),
        token(QueryTokenType.LeftSquareBracket),
        token(QueryTokenType.Text, "20241201"),
        token(QueryTokenType.Whitespace, " "),
        token(QueryTokenType.Text, "TO"),
        token(QueryTokenType.Whitespace, " "),
        token(QueryTokenType.Text, "20250101"),
        token(QueryTokenType.RightSquareBracket),
        ),
    )
  }

  fun assertTokens(actual: List<QueryToken>, expected: List<QueryToken>) {
    actual.shouldBeSameSizeAs(expected)
    actual.forEachIndexed { index, element ->
      expected[index].type.shouldBe(element.type)
      expected[index].value.shouldBe(element.value)
    }
  }

  fun token(type: QueryTokenType, value: String? = null): QueryToken {
    return QueryToken(type, value)
  }
}
