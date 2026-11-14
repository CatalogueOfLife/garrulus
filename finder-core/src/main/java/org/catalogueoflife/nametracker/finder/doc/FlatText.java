package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks of a document joined by a blank line into one string, with an exact mapping between flat code point offsets
 * and (block, offset) pairs. All offsets are Unicode code points, never UTF-16 chars.
 */
public final class FlatText {
  private static final String SEPARATOR = "\n\n";
  private static final int SEP_LEN = SEPARATOR.length();

  /** A code point offset within a block. */
  public record Position(String blockId, int offset) {}

  private record Seg(Block block, int start, int length) {}

  private final String text;
  private final List<Seg> segs;
  private final Map<String, Seg> byId = new HashMap<>();

  private FlatText(List<Block> blocks) {
    StringBuilder sb = new StringBuilder();
    List<Seg> list = new ArrayList<>(blocks.size());
    int cp = 0;
    for (Block b : blocks) {
      if (!list.isEmpty()) {
        sb.append(SEPARATOR);
        cp += SEP_LEN;
      }
      int len = CodePoints.length(b.text());
      Seg s = new Seg(b, cp, len);
      list.add(s);
      byId.put(b.id(), s);
      sb.append(b.text());
      cp += len;
    }
    this.text = sb.toString();
    this.segs = List.copyOf(list);
  }

  public static FlatText of(Document doc) {
    return new FlatText(doc.blocks());
  }

  public String text() {
    return text;
  }

  /**
   * Maps a flat offset to its block. The exclusive end offset of a block maps to (block, length), so that span ends
   * round trip. An offset inside a separator, or outside the text, is an {@link IllegalArgumentException}.
   */
  public Position toBlock(int flatCp) {
    int lo = 0;
    int hi = segs.size() - 1;
    Seg found = null;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      Seg s = segs.get(mid);
      if (flatCp < s.start) hi = mid - 1;
      else if (flatCp > s.start + s.length) lo = mid + 1;
      else {
        found = s;
        break;
      }
    }
    if (found == null) throw new IllegalArgumentException("Flat offset " + flatCp + " is not inside a block");
    return new Position(found.block.id(), flatCp - found.start);
  }

  /** Flat offset of a block offset in [0, block length]. */
  public int toFlat(String blockId, int blockCp) {
    Seg s = byId.get(blockId);
    if (s == null) throw new IllegalArgumentException("Unknown block " + blockId);
    if (blockCp < 0 || blockCp > s.length) {
      throw new IllegalArgumentException("Offset " + blockCp + " outside block " + blockId + " of length " + s.length);
    }
    return s.start + blockCp;
  }

  /**
   * Splits at block boundaries only, filling each chunk up to maxCodePoints (separators included). A single block longer
   * than the limit becomes a chunk of its own. Every chunk has its own mapping, starting at 0.
   */
  public List<FlatText> chunks(int maxCodePoints) {
    List<FlatText> out = new ArrayList<>();
    List<Block> cur = new ArrayList<>();
    int curLen = 0;
    for (Seg s : segs) {
      int add = cur.isEmpty() ? s.length : SEP_LEN + s.length;
      if (!cur.isEmpty() && curLen + add > maxCodePoints) {
        out.add(new FlatText(cur));
        cur = new ArrayList<>();
        add = s.length;
        curLen = 0;
      }
      cur.add(s.block);
      curLen += add;
    }
    if (!cur.isEmpty()) out.add(new FlatText(cur));
    return out;
  }
}
