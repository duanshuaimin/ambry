package com.github.ambry.store;

import com.github.ambry.utils.Scheduler;
import com.github.ambry.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The set of data stored in the index
 */

class BlobIndexValue {
  private final long size;
  private final long offset;
  private byte flags;
  private long timeToLive;

  BlobIndexValue(long size, long offset, byte flags, long timeToLive) {
    this.size = size;
    this.offset = offset;
    this.flags = flags;
    this.timeToLive = timeToLive;
  }

  public long getSize() {
    return size;
  }

  public long getOffset() {
    return offset;
  }

  public byte getFlags() {
    return flags;
  }

  public long getTimeToLive() {
    return timeToLive;
  }
}

/**
 * A key and value that represents an index entry
 */
class BlobIndexEntry {
  private IndexKey key;
  private BlobIndexValue value;

  public BlobIndexEntry(IndexKey key, BlobIndexValue value) {
    this.key = key;
    this.value = value;
  }

  public IndexKey getKey() {
    return this.key;
  }

  public BlobIndexValue getValue() {
    return this.value;
  }
}

/**
 * The index implementation that is responsible for adding and modifying index entries,
 * recovering an index from the log and commit and recover index to disk
 */
public class BlobIndex {
  private ConcurrentHashMap<IndexKey, BlobIndexValue> index = new ConcurrentHashMap<IndexKey, BlobIndexValue>();
  private AtomicLong logEndOffset;
  private BlobJournal indexJournal;
  private File indexFile;
  private static final String indexFileName = "index_current";
  private IndexPersistor persistor;
  private Scheduler scheduler;
  private Log log;
  private Logger logger = LoggerFactory.getLogger(getClass());

  BlobIndex(String datadir, Scheduler scheduler, Log log) throws IndexCreationException  {
    try {
      logEndOffset = new AtomicLong(0);
      indexJournal = new BlobJournal();
      this.log = log;
      // check if file exist and recover from it
      indexFile = new File(datadir, indexFileName);
      persistor = new IndexPersistor(indexFile);
      persistor.read();
      logger.info("read index from file {}", datadir);

      // start scheduler thread to persist index in the background
      this.scheduler = scheduler;
      this.scheduler.schedule("index persistor", persistor, 5000, 60000, TimeUnit.MILLISECONDS);
    }
    catch (IndexCreationException e) {
      // ignore any exception while reading the index file
      // reset to recover from start
      logEndOffset.set(0);
      index.clear();
    }
    catch (Exception e) {
      throw new IndexCreationException("Error while creating index " + e);
    }
  }

  public void AddToIndex(BlobIndexEntry entry, long fileEndOffset) {
    if (this.logEndOffset.get() >= fileEndOffset) {
      throw new IllegalArgumentException("File end offset provided to the index is less than the current end offset");
    }
    index.put(entry.getKey(), entry.getValue());
    this.logEndOffset.set(fileEndOffset);
  }

  public void AddToIndex(ArrayList<BlobIndexEntry> entries, long fileEndOffset) {
    if (this.logEndOffset.get() >fileEndOffset) {
      throw new IllegalArgumentException("File end offset provided to the index is less than the current end offset");
    }
    for (BlobIndexEntry entry : entries) {
      index.put(entry.getKey(), entry.getValue());
    }
    this.logEndOffset.set(fileEndOffset);
  }

  public boolean exist(IndexKey key) {
    return index.containsKey(key);
  }

  public ArrayList<IndexKey> findMissingEntries(ArrayList<IndexKey> keys) {
    ArrayList<IndexKey> missingEntries = new ArrayList<IndexKey>();
    for (IndexKey key : keys) {
      if (!exist(key))
        missingEntries.add(key);
    }
    return missingEntries;
  }

  public BlobIndexValue getValue(IndexKey key) {
    return index.get(key);
  }

  // TODO need to fix this
  public ArrayList<BlobIndexValue> getIndexEntrySince(long offset) {
    // check journal
    // return complete index
    return null;
  }

  public void close() throws IOException {
    persistor.write();
  }

  public long getCurrentEndOffset() {
    return logEndOffset.get();
  }

  class IndexPersistor implements Runnable {

    private Object lock = new Object();
    private final File file;
    private Short version = 0;
    private IndexKeyFactory factory;

    public IndexPersistor(File file)
            throws IOException, InstantiationException, IllegalAccessException, ClassNotFoundException {
      new File(file + ".tmp").delete();
      this.file = file;
      file.createNewFile();
      factory = Utils.getObj("com.github.ambry.shared.BlobIdFactory");
    }

    public void write() throws IOException {
      logger.info("writing index to disk for {}", indexFile.getPath());
      synchronized(lock) {
        // write to temp file and then swap with the existing file
        File temp = new File(file.getAbsolutePath() + ".tmp");
        BufferedWriter writer = new BufferedWriter(new FileWriter(temp));
        try {
          // write the current version
          writer.write(version.toString());
          writer.newLine();

          // before iterating the map, get the current file end pointer
          long fileEndPointer = logEndOffset.get();

          // flush the log to ensure everything till the fileEndPointer is flushed
          log.flush();

          // write the entries
          for (Map.Entry<IndexKey, BlobIndexValue> entry : index.entrySet()) {
            writer.write(entry.getKey().toString() + " " + entry.getValue().getOffset() + " " +
                    entry.getValue().getSize() + " " + entry.getValue().getFlags() + " " +
                    entry.getValue().getTimeToLive());
            writer.newLine();
          }
          writer.write("fileendpointer " + fileEndPointer);

          // flush and overwrite old file
          writer.flush();
          // swap temp file with the original file
          temp.renameTo(file);
        } finally {
          writer.close();
        }
      }
      logger.info("Completed writing index to file");
    }

    public void run() {
      try {
        write();
      }
      catch (Exception e) {
        logger.info("Error while persisting the index to disk {}", e);
      }
    }

    public void read() throws IOException, IndexCreationException {
      logger.info("Reading index from file {}", indexFile.getPath());
      synchronized(lock) {
        index.clear();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
          String line = reader.readLine();
          if(line == null)
            throw new IndexCreationException("file does not have any lines");
           Short version = (short)Integer.parseInt(line);
           switch (version) {
             case 0:
               line = reader.readLine();
               if(line == null)
                   throw new IndexCreationException("file does not have any lines");
               String[] fields = null;
               while(line != null) {
                 fields = line.split("\\s+");
                 if (fields.length == 2 && fields[0].compareTo("fileendpointer") == 0) {
                   break;
                 }
                 else if(fields.length == 5) {
                   // get key
                   IndexKey key = factory.getKey(fields[0]);
                   long offset = Long.parseLong(fields[1]);
                   long size = Long.parseLong(fields[2]);
                   byte flags = Byte.parseByte(fields[3]);
                   long timeToLive = Long.parseLong(fields[4]);
                   index.put(key, new BlobIndexValue(size, offset, flags, timeToLive));
                   logger.trace("Index entry key : {} -> size: {} offset: {} flags: {} timeToLive: {}",
                           key, size, offset, flags, timeToLive);
                 }
                 else
                   throw new IOException("Malformed line in index file: '%s'.".format(line));
                 line = reader.readLine();
               }
               logEndOffset.set(Long.parseLong(fields[1]));
               logger.trace("logEndOffset " + logEndOffset.get());
               break;
             default:
               throw new IndexCreationException("version of index file not supported");
           }
        } finally {
              reader.close();
        }
      }
    }
  }
}