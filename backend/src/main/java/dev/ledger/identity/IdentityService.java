package dev.ledger.identity;

import dev.ledger.shared.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
  private final Support s;
  private final PasswordEncoder encoder;

  public IdentityService(Support s, PasswordEncoder encoder) {
    this.s = s;
    this.encoder = encoder;
  }

  public Actor current() {
    return byEmail(SecurityContextHolder.getContext().getAuthentication().getName());
  }

  public Actor byEmail(String email) {
    var u =
        s.one("SELECT id,email,role,display_name FROM users WHERE email=? AND enabled=true", email);
    return new Actor(
        Support.uuid(u.get("id")),
        u.get("email").toString(),
        u.get("role").toString(),
        u.get("display_name").toString());
  }

  public Actor byId(UUID id) {
    return byEmail(s.one("SELECT email FROM users WHERE id=?", id).get("email").toString());
  }

  @Transactional
  public Map<String, Object> register(Map<String, String> input) {
    String email = Support.text(input.get("email"), 180, "Email").toLowerCase(Locale.ROOT);
    if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw new ApiException(400, "INVALID_EMAIL", "Enter a valid email address.");
    String name = Support.text(input.get("name"), 80, "Name");
    String password = input.get("password");
    if (password == null || password.length() < 12 || password.length() > 64)
      throw new ApiException(
          400, "INVALID_PASSWORD", "Use a password between 12 and 64 characters.");
    UUID id = UUID.randomUUID();
    s.db.update(
        "INSERT INTO users(id,email,display_name,password_hash,role) VALUES(?,?,?,?, 'CUSTOMER')",
        id,
        email,
        name,
        encoder.encode(password));
    s.audit(id, "REGISTER", id, "SUCCESS", "Customer registration");
    return Map.of("id", id, "email", email, "name", name, "role", "CUSTOMER");
  }
}
