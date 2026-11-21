package org.catalogueoflife.nametracker.store.search;

import java.util.List;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The filters shared by work search, its count and its facets. Null means "no restriction". {@code taxon} is a CLB
 * usage id and matches works that name it or any descendant (via work_name.taxon_path); {@code name} is a name_string
 * id; {@code taxgroup} is hierarchical because work.taxgroups already holds the ancestor groups; {@code identifier}
 * is a normalised external identifier the work holds (work_identifier). Build it with {@link #builder()}: new filters
 * add a setter, not a constructor argument at every call site.
 */
public record WorkFilter(
    @Nullable String taxon,
    @Nullable Long name,
    @Nullable String code,
    @Nullable String taxgroup,
    @Nullable String subfield,
    @Nullable String journalType,
    @Nullable String tier,
    @Nullable String oa,
    @Nullable String country,
    @Nullable Long source,
    @Nullable Integer yearFrom,
    @Nullable Integer yearTo,
    @Nullable Boolean hasActs,
    @Nullable String actType,
    boolean mainSubjectOnly,
    @Nullable String q,
    @Nullable Identifier identifier,
    List<String> mentionedIn) {

  public WorkFilter {
    mentionedIn = mentionedIn == null ? List.of() : mentionedIn;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Unset means no restriction. */
  public static final class Builder {
    private @Nullable String taxon;
    private @Nullable Long name;
    private @Nullable String code;
    private @Nullable String taxgroup;
    private @Nullable String subfield;
    private @Nullable String journalType;
    private @Nullable String tier;
    private @Nullable String oa;
    private @Nullable String country;
    private @Nullable Long source;
    private @Nullable Integer yearFrom;
    private @Nullable Integer yearTo;
    private @Nullable Boolean hasActs;
    private @Nullable String actType;
    private boolean mainSubjectOnly;
    private @Nullable String q;
    private @Nullable Identifier identifier;
    private List<String> mentionedIn = List.of();

    public Builder taxon(@Nullable String v) {
      this.taxon = v;
      return this;
    }

    public Builder name(@Nullable Long v) {
      this.name = v;
      return this;
    }

    public Builder code(@Nullable String v) {
      this.code = v;
      return this;
    }

    public Builder taxgroup(@Nullable String v) {
      this.taxgroup = v;
      return this;
    }

    public Builder subfield(@Nullable String v) {
      this.subfield = v;
      return this;
    }

    public Builder journalType(@Nullable String v) {
      this.journalType = v;
      return this;
    }

    public Builder tier(@Nullable String v) {
      this.tier = v;
      return this;
    }

    public Builder oa(@Nullable String v) {
      this.oa = v;
      return this;
    }

    public Builder country(@Nullable String v) {
      this.country = v;
      return this;
    }

    public Builder source(@Nullable Long v) {
      this.source = v;
      return this;
    }

    public Builder yearFrom(@Nullable Integer v) {
      this.yearFrom = v;
      return this;
    }

    public Builder yearTo(@Nullable Integer v) {
      this.yearTo = v;
      return this;
    }

    public Builder hasActs(@Nullable Boolean v) {
      this.hasActs = v;
      return this;
    }

    public Builder actType(@Nullable String v) {
      this.actType = v;
      return this;
    }

    public Builder mainSubjectOnly(boolean v) {
      this.mainSubjectOnly = v;
      return this;
    }

    public Builder q(@Nullable String v) {
      this.q = v;
      return this;
    }

    public Builder identifier(@Nullable Identifier v) {
      this.identifier = v;
      return this;
    }

    /** Parts of a work the name must occur in ({@code TITLE}, {@code ABSTRACT}, {@code BODY}); empty means anywhere. */
    public Builder mentionedIn(@Nullable List<String> v) {
      this.mentionedIn = v == null ? List.of() : v;
      return this;
    }

    public WorkFilter build() {
      return new WorkFilter(taxon, name, code, taxgroup, subfield, journalType, tier, oa, country, source, yearFrom, yearTo, hasActs, actType, mainSubjectOnly, q, identifier, mentionedIn);
    }
  }
}
