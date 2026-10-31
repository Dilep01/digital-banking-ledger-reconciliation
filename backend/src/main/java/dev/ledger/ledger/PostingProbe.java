package dev.ledger.ledger;

import org.springframework.stereotype.Component;

/** A no-op production seam for deterministic failure-injection tests. */
@Component
public class PostingProbe {
  public void afterFirstBalanceWrite() {}
}
