package org.catalogueoflife.nametracker.store.index;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface IndexMapper {

  /** Upserts the run (identity is name, version, configHash and dictionaryKey, null included) and returns its id. */
  long finderRunId(@Param("o") FinderRun o);

  /** Upserts by name and returns the id; kind and rank of an existing row are never changed. */
  long nameStringId(@Param("name") String name, @Param("kind") String kind, @Param("rank") @Nullable String rank);

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

  @Nullable NameMatch match(@Param("nameStringId") long nameStringId, @Param("hintKey") String hintKey,
      @Param("releaseKey") int releaseKey);

  /** Upsert by (nameStringId, hintKey, releaseKey); matchedAt is reset to now. */
  void putMatch(@Param("o") NameMatch o);

  void insertWorkNames(@Param("list") List<WorkName> list);

  List<WorkName> workNames(@Param("workId") long workId);

  @Nullable XrRelease currentRelease();

  boolean releaseExists(@Param("key") int key);

  void addRelease(@Param("key") int key, @Param("alias") String alias, @Param("current") boolean current);

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

  /** Sets state MATCHED. */
  void setMatched(@Param("workId") long workId);

  /** Sets codes, taxgroups and has_acts, and also state INDEXED and indexed_at = now(). */
  default void setIndexed(long workId, String[] codes, String[] taxgroups, boolean hasActs) {
    updateIndexed(workId, List.of(codes), List.of(taxgroups), hasActs);
  }

  void updateIndexed(@Param("workId") long workId, @Param("codes") List<String> codes,
      @Param("taxgroups") List<String> taxgroups, @Param("hasActs") boolean hasActs);
}
