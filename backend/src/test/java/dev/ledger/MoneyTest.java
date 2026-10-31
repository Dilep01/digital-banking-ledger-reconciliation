package dev.ledger;

import static org.assertj.core.api.Assertions.*;

import dev.ledger.shared.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class MoneyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "-1",
        "1.001",
        "NaN",
        "Infinity",
        "1000000.01",
        "1e3",
        " 1",
        "1,000.00",
        "",
        ".01"
      })
  void rejectsInvalidMoney(String value) {
    assertThatThrownBy(() -> Support.money(value)).isInstanceOf(ApiException.class);
  }

  @ParameterizedTest
  @CsvSource({"1,1.00", "0.01,0.01", "500.1,500.10", "1000000,1000000.00"})
  void exactDecimals(String input, String expected) {
    assertThat(Support.money(input).toPlainString()).isEqualTo(expected);
  }
}
