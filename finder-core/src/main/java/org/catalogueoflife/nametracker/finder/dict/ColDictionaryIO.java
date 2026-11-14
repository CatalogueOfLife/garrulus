package org.catalogueoflife.nametracker.finder.dict;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.gbif.nameparser.api.Rank;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Versioned binary format: magic, version, release key, rank name table, then the genus, epithet and ambiguous hash
 * sets and the uninomial hash to rank-index map. Ranks are stored by name; a name the current Rank enum no longer has is read as UNRANKED, so the file survives a changed enum.
 */
public final class ColDictionaryIO {
  static final int MAGIC = 0x434F4C44; // "COLD"
  static final int VERSION = 1;

  private ColDictionaryIO() {}

  public static void write(ColDictionary d, Path file) throws IOException {
    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeUTF(d.releaseKey());
      Rank[] table = d.rankTable();
      out.writeShort(table.length);
      for (Rank r : table) out.writeUTF(r.name());
      writeSet(out, d.genera());
      writeSet(out, d.epithets());
      writeSet(out, d.ambiguous());
      Long2ByteOpenHashMap m = d.uninomialRanks();
      out.writeInt(m.size());
      for (var e : m.long2ByteEntrySet()) {
        out.writeLong(e.getLongKey());
        out.writeByte(e.getByteValue());
      }
    }
  }

  private static void writeSet(DataOutputStream out, LongOpenHashSet s) throws IOException {
    out.writeInt(s.size());
    LongIterator it = s.iterator();
    while (it.hasNext()) out.writeLong(it.nextLong());
  }

  public static ColDictionary read(Path file) throws IOException {
    try {
      return doRead(file);
    } catch (EOFException e) {
      throw new IOException("Dictionary file " + file + " is truncated or corrupt", e);
    }
  }

  private static int count(DataInputStream in, long fileSize, Path file) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > fileSize / 8) throw new IOException("Dictionary file " + file + " is corrupt: implausible count " + n);
    return n;
  }

  private static ColDictionary doRead(Path file) throws IOException {
    long size = Files.size(file);
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
      if (in.readInt() != MAGIC) throw new IOException("Not a dictionary file (bad magic): " + file);
      int v = in.readInt();
      if (v != VERSION) throw new IOException("Unsupported dictionary version " + v + " in " + file);
      String key = in.readUTF();
      int n = in.readUnsignedShort();
      // file index -> current Rank
      Rank[] stored = new Rank[n];
      for (int i = 0; i < n; i++) {
        String name = in.readUTF();
        Rank r;
        try {
          r = Rank.valueOf(name);
        } catch (IllegalArgumentException e) {
          r = Rank.UNRANKED;
        }
        stored[i] = r;
      }
      LongOpenHashSet genera = readSet(in, size, file);
      LongOpenHashSet epithets = readSet(in, size, file);
      LongOpenHashSet ambiguous = readSet(in, size, file);
      int mapSize = count(in, size, file);
      Long2ByteOpenHashMap m = new Long2ByteOpenHashMap(mapSize);
      for (int i = 0; i < mapSize; i++) {
        long h = in.readLong();
        m.put(h, in.readByte());
      }
      return new ColDictionary(key, genera, epithets, m, stored, ambiguous);
    }
  }

  private static LongOpenHashSet readSet(DataInputStream in, long fileSize, Path file) throws IOException {
    int n = count(in, fileSize, file);
    LongOpenHashSet s = new LongOpenHashSet(n);
    for (int i = 0; i < n; i++) s.add(in.readLong());
    return s;
  }
}
