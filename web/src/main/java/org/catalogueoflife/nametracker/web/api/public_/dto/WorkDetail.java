package org.catalogueoflife.nametracker.web.api.public_.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import org.catalogueoflife.nametracker.store.ident.IdentifierLink;
import org.jspecify.annotations.Nullable;

/**
 * text is null unless the work has a retained full-text document; snippets is empty when text is served.
 * abstractMentions and abstractActs are code-point offsets into the abstract, titleMentions into the title.
 */
public record WorkDetail(long id, @Nullable String doi, @Nullable String title, @Nullable Integer year,
    @Nullable LocalDate publicationDate, @Nullable SourceRef source, List<Author> authors, @Nullable String volume,
    @Nullable String issue, @Nullable String pages, @Nullable String language, @Nullable String type,
    @Nullable String oaStatus, @Nullable String bestOaUrl, @JsonProperty("abstract") @Nullable String abstractText,
    @Nullable String licence, boolean retained, @Nullable String textLevel, boolean hasActs, List<String> codes,
    List<String> taxgroups, List<NameDetail> names, List<ContextDto> contexts, @Nullable List<TextBlock> text,
    List<MentionSpan> titleMentions, List<MentionSpan> abstractMentions, List<ActSpan> abstractActs,
    List<Snippet> snippets, List<IdentifierLink> identifiers, @Nullable String languageSource,
    @Nullable String titleLang, @Nullable String abstractLang, List<TextVersion> titles, List<TextVersion> abstracts) {}
