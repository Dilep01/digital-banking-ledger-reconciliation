package dev.ledger.identity;

import dev.ledger.shared.ApiException;
import java.util.UUID;

public record Actor(UUID id, String email, String role, String name) {
  public boolean customer() {
    return role.equals("CUSTOMER");
  }

  public void require(String... roles) {
    for (String allowed : roles) if (role.equals(allowed)) return;
    throw new ApiException(403, "FORBIDDEN", "Your role cannot perform this action.");
  }
}
