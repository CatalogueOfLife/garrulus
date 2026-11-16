package org.catalogueoflife.nametracker.web.me;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.store.account.HitMapper;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.web.api.public_.WorkController;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkSummary;
import org.catalogueoflife.nametracker.web.auth.CurrentUser;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The signed-in user's hits, newest first, each with the work as the public API shows it. */
@RestController
public class HitController {

  /** {@code work} is null when the work is no longer listed. */
  public record HitDto(long id, String kind, JsonNode names, long subscriptionId, @Nullable String subscriptionLabel,
      Instant created, @Nullable WorkSummary work) {}

  public record HitPage(int limit, int offset, List<HitDto> results) {}

  private final HitMapper hits;
  private final SubscriptionMapper subscriptions;
  private final WorkController works;
  private final CurrentUser currentUser;

  public HitController(HitMapper hits, SubscriptionMapper subscriptions, WorkController works,
      CurrentUser currentUser) {
    this.hits = hits;
    this.subscriptions = subscriptions;
    this.works = works;
    this.currentUser = currentUser;
  }

  @GetMapping("/api/me/hits")
  public HitPage list(@RequestParam(required = false) @Nullable Long subscription,
      @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
    long userId = currentUser.require();
    WorkController.checkPaging(limit, offset);
    List<Hit> rows = hits.listByUser(userId, subscription, limit, offset);
    Map<Long, String> labels = subscriptions.listByUser(userId).stream()
        .collect(Collectors.toMap(Subscription::id, Subscription::label));
    Map<Long, WorkSummary> summaries = works.summaries(rows.stream().map(Hit::workId).distinct().toList());
    List<HitDto> out = rows.stream().map(h -> new HitDto(h.id(), h.kind(), names(h.names()), h.subscriptionId(),
        labels.get(h.subscriptionId()), h.created(), summaries.get(h.workId()))).toList();
    return new HitPage(limit, offset, out);
  }

  static JsonNode names(@Nullable String json) {
    return FinderJson.mapper().readTree(json == null ? "[]" : json);
  }
}
