package app.scholarai;

import static app.scholarai.Accounts.*;

import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Composition: combines validated planning inputs with the user's persisted plan. */
@Service
public class FinancialAid {
  private final Store store;

  public FinancialAid(Store store) {
    this.store = store;
  }

  public Map<String, Object> load(String user) {
    var row = store.one("SELECT payload FROM aid_plans WHERE user_id=?", user);
    return row == null
        ? Map.of(
            "currency",
            "USD",
            "cost",
            24000,
            "confirmed",
            4000,
            "contribution",
            6000,
            "checked",
            List.of())
        : store.object(row.get("payload").toString());
  }

  public double gap(Map<String, Object> plan) {
    return Math.max(
        0,
        Matching.number(plan, "cost")
            - Matching.number(plan, "confirmed")
            - Matching.number(plan, "contribution"));
  }

  @Transactional
  public void save(String user, Map<String, String> form, List<String> checked) throws IOException {
    // Lock a stable parent row so two first saves cannot both insert the same user's plan.
    require(
        store.one("SELECT id FROM users WHERE id=? FOR UPDATE", user) != null, "User not found.");
    String currency = text(form, "currency", 3);
    require(
        Set.of("USD", "INR", "GBP", "EUR", "CAD", "AUD").contains(currency),
        "Choose a supported currency.");
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("currency", currency);
    for (String key : List.of("cost", "confirmed", "contribution"))
      plan.put(key, Scholarships.decimal(form, key, 0, 1000000000));
    Set<String> valid = new HashSet<>();
    store.list(Bootstrap.read("checklist.json")).forEach(t -> valid.add(t.get("id").toString()));
    plan.put(
        "checked",
        checked == null ? List.of() : checked.stream().filter(valid::contains).distinct().toList());
    int updated =
        store
            .jdbc()
            .update("UPDATE aid_plans SET payload=? WHERE user_id=?", store.json(plan), user);
    if (updated == 0)
      store
          .jdbc()
          .update("INSERT INTO aid_plans(user_id,payload) VALUES (?,?)", user, store.json(plan));
  }
}
