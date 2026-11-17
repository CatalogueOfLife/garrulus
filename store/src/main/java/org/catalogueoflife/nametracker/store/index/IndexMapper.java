package org.catalogueoflife.nametracker.store.index;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface IndexMapper {

  /** Upserts the run (identity is name, version, configHash and dictionaryKey, null included) and returns its id. */
  long finderRunId(@Param("o") FinderRun o);

  /**
   * The id of the name string, inserted if new; kind and rank of an existing row are never changed, and an existing
   * row is not written to (no row lock, no dead tuple).
   */
  default long nameStringId(String name, String kind, @Nullable String rank) {
    Long id = insertNameString(name, kind, rank);
    // null only when a concurrent transaction committed the name after this statement's snapshot: look again
    return id != null ? id : nameStringIdByName(name);
  }

  /** Inserts the name unless it exists; the id of the new or the existing row, null if the latter is not visible. */
  @Nullable Long insertNameString(@Param("name") String name, @Param("kind") String kind,
      @Param("rank") @Nullable String rank);

  long nameStringIdByName(@Param("name") String name);

  /** Deletes the mentions, contexts and work_name rows of the work. */
  default void deleteWorkIndex(long workId) {
    deleteMentions(workId);
    deleteContexts(workId);
    deleteWorkNames(workId);
  }

  void deleteMentions(@Param("workId") long workId);

  void deleteContexts(@Param("workId") long workId);

  void deleteWorkNames(@Param("workId") long workId);

  /** Inserts the context (its id is ignored) and returns the generated id. */
  long insertContext(@Param("o") ContextRow o);

  void insertMentions(@Param("list") List<MentionRow> list);

  List<MentionRow> mentions(@Param("workId") long workId);

  List<ContextRow> contexts(@Param("workId") long workId);

  // The pending index: a re-index of a listed work is written here and swapped in by swapPendingIndex.

  /** As {@link #insertContext}, into the pending index; the id comes from the live table's sequence. */
  long insertPendingContext(@Param("o") ContextRow o);

  void insertPendingMentions(@Param("list") List<MentionRow> list);

  List<MentionRow> pendingMentions(@Param("workId") long workId);

  List<ContextRow> pendingContexts(@Param("workId") long workId);

  /** Deletes the pending mentions and contexts of the work. */
  default void deletePendingIndex(long workId) {
    deletePendingMentions(workId);
    deletePendingContexts(workId);
  }

  void deletePendingMentions(@Param("workId") long workId);

  void deletePendingContexts(@Param("workId") long workId);

  /** Sets pending_finder_run, pending_doc_sha and pending_diagnostics (a JSON object); state and live index stay. */
  void setPending(@Param("workId") long workId, @Param("finderRunId") long finderRunId,
      @Param("docSha") String docSha, @Param("json") String json);

  /** The finder run of the work's pending index, null if it has none. */
  @Nullable Long pendingFinderRun(@Param("workId") long workId);

  /** The sha of the document of the work's pending index, null if it has none. */
  @Nullable String pendingDocSha(@Param("workId") long workId);

  /** The row set to place and index: the pending index if the work has one, else the live index; null if no work. */
  @Nullable IndexRows indexRows(@Param("workId") long workId);

  /**
   * Replaces the live mentions and contexts of the work by its pending ones, keeping their ids (contexts first), empties
   * the pending index and makes the pending run, document sha and diagnostics the current ones. The caller holds the
   * work lock; work_name rows are left to the caller.
   */
  default void swapPendingIndex(long workId) {
    deleteMentions(workId);
    deleteContexts(workId);
    movePendingContexts(workId);
    movePendingMentions(workId);
    deletePendingIndex(workId);
    adoptPendingRun(workId);
  }

  void movePendingContexts(@Param("workId") long workId);

  void movePendingMentions(@Param("workId") long workId);

  /** current_finder_run, names_doc_sha, finder_diagnostics from the pending columns, which are cleared. */
  void adoptPendingRun(@Param("workId") long workId);

  /** The subscriptions the work's previous index matched, null if unknown (a first indexing). */
  default void setReindexPrevSubscriptions(long workId, long @Nullable [] subscriptionIds) {
    updateReindexPrevSubscriptions(workId,
        subscriptionIds == null ? null : java.util.Arrays.stream(subscriptionIds).boxed().toList());
  }

  /** The ids stored by {@link #setReindexPrevSubscriptions}; empty when none or unknown (a first indexing). */
  List<Long> reindexPrevSubscriptions(@Param("workId") long workId);

  void updateReindexPrevSubscriptions(@Param("workId") long workId, @Param("ids") @Nullable List<Long> ids);

  @Nullable NameMatch match(@Param("nameStringId") long nameStringId, @Param("hintKey") String hintKey,
      @Param("releaseKey") int releaseKey);

  /** Upsert by (nameStringId, hintKey, releaseKey); matchedAt is reset to now. */
  void putMatch(@Param("o") NameMatch o);

  void insertWorkNames(@Param("list") List<WorkName> list);

  /** The text level of the work (FULLTEXT, ABSTRACT_ONLY, NO_TEXT), null if unset. */
  @Nullable String textLevel(@Param("workId") long workId);

  /** The ubiquity of the given name strings; names without a row are left out. */
  List<NameUbiquity> ubiquity(@Param("ids") java.util.Collection<Long> ids);

  List<WorkName> workNames(@Param("workId") long workId);

  /** The name strings of the ids; ids without a row are left out. */
  List<NameString> nameStrings(@Param("ids") List<Long> ids);

  @Nullable WorkFacts workFacts(@Param("workId") long workId);

  @Nullable XrRelease currentRelease();

  boolean releaseExists(@Param("key") int key);

  /** Inserts the release, in state CURRENT if current, else SEEN. */
  void addRelease(@Param("key") int key, @Param("alias") String alias, @Param("current") boolean current);

  // The XR switch (states in XrRelease). Its steps lock all releases first, so that they run one at a time.

  @Nullable XrRelease release(@Param("key") int key);

  /** Takes the row lock of every release until the end of the transaction; returns their keys. */
  List<Integer> lockReleases();

  /** A release in PREPARING, PREPARED or SWITCHING, the lowest key if several; null if no switch runs. */
  @Nullable XrRelease runningSwitch();

  /** State PREPARING, switch_started now, the note; switched_at, keys_total and keys_lost cleared. */
  void startSwitch(@Param("key") int key, @Param("note") String note);

  /** Sets state and note. */
  void setReleaseState(@Param("key") int key, @Param("state") String state, @Param("note") @Nullable String note);

  /** Sets the state to {@code to} only if it is {@code from}; returns the rows changed (0 or 1). */
  int changeReleaseState(@Param("key") int key, @Param("from") String from, @Param("to") String to);

  void setReleaseKeys(@Param("key") int key, @Param("total") int total, @Param("lost") int lost);

  /** The current release stops being current: current false, the given state and note. */
  void retireRelease(@Param("key") int key, @Param("state") String state, @Param("note") @Nullable String note);

  /** The release becomes the current one: current true, state SWITCHING, switched_at now. Retire the old one first. */
  void switchToRelease(@Param("key") int key);

  /** Keys of the release's name_match rows after (afterName, afterHint), ordered by name string id and hint key. */
  List<MatchKey> matchKeys(@Param("releaseKey") int releaseKey, @Param("afterName") long afterName,
      @Param("afterHint") String afterHint, @Param("limit") int limit);

  /** Keys matched in {@code current} and how many of them are lost in {@code candidate}, see {@link MatchLoss}. */
  MatchLoss matchLoss(@Param("current") int current, @Param("candidate") int candidate);

  /** Sets current_finder_run, names_doc_sha and state NAMES_FOUND. */
  void setNamesFound(@Param("workId") long workId, @Param("finderRunId") long finderRunId,
      @Param("docSha") String docSha);

  /** The sha of the document the finder last saw for the work, null if it never ran. */
  @Nullable String namesDocSha(@Param("workId") long workId);

  /** Takes the row lock of the work until the end of the transaction, so that finder runs on one work are serial. */
  @Nullable Long lockWork(@Param("workId") long workId);

  /** The finder run that last named the work, null if none. */
  @Nullable Long currentFinderRun(@Param("workId") long workId);

  /** Replaces the finder diagnostics (a JSON object) of the work. */
  void setDiagnostics(@Param("workId") long workId,
      @Param("json") String json);

  /** Ids of works in CONTENT_RESOLVED that the finder never saw, newest first. */
  List<Long> pendingNames(@Param("limit") int limit);

  @Nullable NameString nameString(@Param("id") long id);


  /**
   * Sets codes, taxgroups and has_acts, and also state INDEXED, indexed_at = now(), placed_release (the XR release the
   * work names were placed in) and, once, first_indexed_at.
   */
  default void setIndexed(long workId, String[] codes, String[] taxgroups, boolean hasActs, int releaseKey) {
    updateIndexed(workId, List.of(codes), List.of(taxgroups), hasActs, releaseKey);
  }

  void updateIndexed(@Param("workId") long workId, @Param("codes") List<String> codes,
      @Param("taxgroups") List<String> taxgroups, @Param("hasActs") boolean hasActs,
      @Param("releaseKey") int releaseKey);

  /**
   * Ids of works the finder has seen ({@code current_finder_run} set) after {@code afterId}, ascending, filtered by
   * the arguments that are not null.
   */
  List<Long> seenWorkIds(@Param("afterId") long afterId, @Param("limit") int limit,
      @Param("sourceId") @Nullable Long sourceId, @Param("workIds") @Nullable List<Long> workIds,
      @Param("yearFrom") @Nullable Integer yearFrom, @Param("yearTo") @Nullable Integer yearTo);

  /** Ids of INDEXED or NOTIFIED works after {@code afterId}, ascending. */
  List<Long> indexedWorkIds(@Param("afterId") long afterId, @Param("limit") int limit);

  /**
   * As {@link #indexedWorkIds}, but only works with a work name whose ubiquity penalty changed (see
   * {@code StatsMapper#changedUbiquityCount}).
   */
  List<Long> changedWorkIds(@Param("afterId") long afterId, @Param("limit") int limit, @Param("full") double full,
      @Param("threshold") double threshold);

  /** Sets salience and main_subject of the work's rows, only where either differs; returns the rows changed. */
  int updateSalience(@Param("workId") long workId, @Param("list") List<WorkName> list);

  /**
   * Sets codes, taxgroups and has_acts of the work, only if one differs, and leaves state and indexed_at alone.
   * Returns 1 if changed.
   */
  int updateWorkColumns(@Param("workId") long workId, @Param("codes") List<String> codes,
      @Param("taxgroups") List<String> taxgroups, @Param("hasActs") boolean hasActs);
}
