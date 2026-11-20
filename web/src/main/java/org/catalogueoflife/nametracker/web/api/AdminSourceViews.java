package org.catalogueoflife.nametracker.web.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.registry.RegistrationMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSample;
import org.catalogueoflife.nametracker.store.registry.SourceSampleMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSignal;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.AdminSourceFlags;
import org.catalogueoflife.nametracker.store.source.ProposalThresholds;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.web.api.AdminRegistrationController.AdminRegistrationView;
import org.catalogueoflife.nametracker.web.api.AdminRouteController.RouteView;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceRef;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the admin endpoints show of a journal: the list row ({@link SourceView}) and the detail
 * ({@link SourceAdminDetail}). The proposals are computed at read time and never applied: a name-density PROMOTE or
 * DEMOTE from the nightly categorisation (thresholds {@code nametracker.admin.proposals.*}, start values), a dormancy
 * proposal the coverage check wrote, and a revival proposal for a ceased or excluded journal with dated signs of life.
 */
@Component
public class AdminSourceViews {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int SAMPLES = 10;

  public record SampleView(Instant sampledAt, @Nullable String route, int articles, int withAbstract,
      @Nullable Float namesPerArticle, @Nullable Float actShare, JsonNode taxgroups) {
    static SampleView of(SourceSample s) {
      return new SampleView(s.sampledAt(), s.route(), s.articles(), s.withAbstract(), s.namesPerArticle(),
          s.actShare(), s.taxgroups() == null ? JSON.createObjectNode() : JSON.readTree(s.taxgroups()));
    }
  }

  public record SourceView(long id, @Nullable String openalexId, String title, String tier, boolean active,
      @Nullable Instant onboardedAt, @Nullable Instant lastHarvested, @Nullable Integer worksPerYear,
      @Nullable String issnL, List<String> issns, String origin, @Nullable String country,
      @Nullable String publisher, @Nullable Float candidateScore, String publicationStatus, boolean dormant,
      boolean revival, @Nullable String proposal, List<String> signalOrigins, @Nullable SampleView sample,
      Map<String, Long> routes, int registrations, @Nullable String ceasedReason, @Nullable Integer lastIssueYear,
      @Nullable Long continuedBy) {}

  public record SignalView(String origin, Instant firstSeen, Instant lastSeen, JsonNode detail) {}

  public record SourceAdminDetail(SourceView source, List<SignalView> signals, List<SampleView> samples,
      List<RouteView> routes, List<AdminRegistrationView> registrations, List<String> disciplines,
      String disciplineSetBy, @Nullable String disciplineSource, List<RawClassification> classifications,
      @Nullable Integer lastIssueYear, @Nullable String ceasedReason, @Nullable SourceRef continuedBy,
      @Nullable LocalDate dormantSince, @Nullable JsonNode dormantEvidence) {}

  private final SourceMapper sources;
  private final SourceSignalMapper signals;
  private final SourceSampleMapper samples;
  private final SourceRouteMapper routes;
  private final RegistrationMapper registrations;
  private final ClassificationRawMapper classifications;
  private final ProposalThresholds thresholds;

  public AdminSourceViews(SourceMapper sources, SourceSignalMapper signals, SourceSampleMapper samples,
      SourceRouteMapper routes, RegistrationMapper registrations, ClassificationRawMapper classifications,
      @Value("${nametracker.admin.proposals.promote-act-rate:0.10}") float promoteActRate,
      @Value("${nametracker.admin.proposals.promote-names:5}") float promoteNames,
      @Value("${nametracker.admin.proposals.demote-core-act-rate:0.02}") float demoteCoreActRate,
      @Value("${nametracker.admin.proposals.demote-core-names:1}") float demoteCoreNames,
      @Value("${nametracker.admin.proposals.demote-broad-names:0.5}") float demoteBroadNames) {
    this.sources = sources;
    this.signals = signals;
    this.samples = samples;
    this.routes = routes;
    this.registrations = registrations;
    this.classifications = classifications;
    this.thresholds = new ProposalThresholds(promoteActRate, promoteNames, demoteCoreActRate, demoteCoreNames,
        demoteBroadNames);
  }

  public ProposalThresholds thresholds() {
    return thresholds;
  }

  /** The list rows of these sources, in the order given. */
  public List<SourceView> views(List<Source> list) {
    Map<Long, AdminSourceFlags> flags = sources.adminFlags(list.stream().map(Source::id).toList(), thresholds).stream()
        .collect(Collectors.toMap(AdminSourceFlags::id, Function.identity()));
    return list.stream().map(s -> view(s, flags.get(s.id()))).toList();
  }

  public SourceView view(Source s) {
    return views(List.of(s)).getFirst();
  }

  private SourceView view(Source s, @Nullable AdminSourceFlags f) {
    SourceSample sample = samples.latest(s.id());
    return new SourceView(s.id(), s.openalexId(), s.title(), s.tier(), Boolean.TRUE.equals(s.active()),
        s.onboardedAt(), s.lastHarvested(), s.worksPerYear(), s.issnL(), s.issns() == null ? List.of() : s.issns(),
        s.origin(), s.country(), s.publisher(), s.candidateScore(), s.publicationStatus(),
        f != null && f.dormant(), f != null && f.revival(), f == null ? null : f.proposal(),
        f == null ? List.of() : f.signalOrigins(), sample == null ? null : SampleView.of(sample),
        f == null ? Collections.emptyMap() : f.routes(), f == null ? 0 : f.registrations(), s.ceasedReason(),
        s.lastIssueYear(), s.continuedBy());
  }

  public SourceAdminDetail detail(Source s) {
    List<AdminRegistrationView> regs = registrations.adminForSource(s.id()).stream()
        .map(AdminRegistrationView::of).toList();
    List<SignalView> sigs = signals.forSource(s.id()).stream().map(g -> signal(g, regs)).toList();
    Source continued = s.continuedBy() == null ? null : sources.get(s.continuedBy());
    return new SourceAdminDetail(view(s), sigs, samples.recent(s.id(), SAMPLES).stream().map(SampleView::of).toList(),
        routes.forSource(s.id()).stream().map(RouteView::of).toList(), regs,
        s.subfields() == null ? List.of() : s.subfields(), s.disciplineSetBy(), s.disciplineSource(),
        classifications.forSource(s.id()), s.lastIssueYear(), s.ceasedReason(),
        continued == null ? null : new SourceRef(continued.id(), continued.title()), s.dormantSince(),
        s.dormantEvidence() == null ? null : JSON.readTree(s.dormantEvidence()));
  }

  /** A REGISTERED signal's key is the registration id: its detail gets the user's note and display name. */
  private static SignalView signal(SourceSignal g, List<AdminRegistrationView> regs) {
    JsonNode detail = JSON.readTree(g.detail());
    if ("REGISTERED".equals(g.origin()) && detail instanceof ObjectNode o) {
      for (AdminRegistrationView r : regs) {
        if (String.valueOf(r.id()).equals(g.signalKey())) {
          if (r.note() != null) o.put("note", r.note());
          if (r.userDisplayName() != null) o.put("userDisplayName", r.userDisplayName());
        }
      }
    }
    return new SignalView(g.origin(), g.firstSeen(), g.lastSeen(), detail);
  }
}
