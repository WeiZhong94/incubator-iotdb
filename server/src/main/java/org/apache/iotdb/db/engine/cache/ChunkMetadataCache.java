/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.engine.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.conf.adapter.IoTDBConfigDynamicAdapter;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.query.control.FileReaderManager;
import org.apache.iotdb.db.utils.FileLoaderUtils;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetadata;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.utils.BloomFilter;
import org.apache.iotdb.tsfile.utils.RamUsageEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to cache <code>List<ChunkMetaData></code> of tsfile in IoTDB. The caching
 * strategy is LRU.
 */
public class ChunkMetadataCache {

  private static final Logger logger = LoggerFactory.getLogger(ChunkMetadataCache.class);
  private static final IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();
  private static final long MEMORY_THRESHOLD_IN_B = config.getAllocateMemoryForChunkMetaDataCache();
  private static final boolean CACHE_ENABLE = config.isMetaDataCacheEnable();

  /**
   * key: file path dot deviceId dot sensorId.
   * <p>
   * value: chunkMetaData list of one timeseries in the file.
   */
  private final LRULinkedHashMap<AccountableString, List<ChunkMetadata>> lruCache;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  private final AtomicLong cacheHitNum = new AtomicLong();
  private final AtomicLong cacheRequestNum = new AtomicLong();


  private ChunkMetadataCache(long memoryThreshold) {
    if (CACHE_ENABLE) {
      logger.info("ChunkMetadataCache size = " + memoryThreshold);
    }
    lruCache = new LRULinkedHashMap<AccountableString, List<ChunkMetadata>>(memoryThreshold) {
      @Override
      protected long calEntrySize(AccountableString key, List<ChunkMetadata> value) {
        if (value.isEmpty()) {
          return RamUsageEstimator.sizeOf(key) + RamUsageEstimator.shallowSizeOf(value);
        }
        long entrySize;
        if (count < 10) {
          long currentSize = value.get(0).calculateRamSize();
          averageSize = ((averageSize * count) + currentSize) / (++count);
          IoTDBConfigDynamicAdapter.setChunkMetadataSizeInByte(averageSize);
          entrySize = RamUsageEstimator.sizeOf(key)
              + (currentSize + RamUsageEstimator.NUM_BYTES_OBJECT_REF) * value.size()
              + RamUsageEstimator.shallowSizeOf(value);
        } else if (count < 100000) {
          count++;
          entrySize = RamUsageEstimator.sizeOf(key)
              + (averageSize + RamUsageEstimator.NUM_BYTES_OBJECT_REF) * value.size()
              + RamUsageEstimator.shallowSizeOf(value);
        } else {
          averageSize = value.get(0).calculateRamSize();
          count = 1;
          entrySize = RamUsageEstimator.sizeOf(key)
              + (averageSize + RamUsageEstimator.NUM_BYTES_OBJECT_REF) * value.size()
              + RamUsageEstimator.shallowSizeOf(value);
        }
        return entrySize;
      }
    };
  }

  public static ChunkMetadataCache getInstance() {
    return ChunkMetadataCacheSingleton.INSTANCE;
  }

  /**
   * get {@link ChunkMetadata}. THREAD SAFE.
   */
  public List<ChunkMetadata> get(String filePath, Path seriesPath)
      throws IOException {
    if (!CACHE_ENABLE) {
      // bloom filter part
      TsFileSequenceReader tsFileReader = FileReaderManager.getInstance().get(filePath, true);
      BloomFilter bloomFilter = tsFileReader.readBloomFilter();
      if (bloomFilter != null && !bloomFilter.contains(seriesPath.getFullPath())) {
        if (logger.isDebugEnabled()) {
          logger.debug(String
              .format("path not found by bloom filter, file is: %s, path is: %s", filePath,
                  seriesPath));
        }
        return new ArrayList<>();
      }
      // If timeseries isn't included in the tsfile, empty list is returned.
      return tsFileReader.getChunkMetadataList(seriesPath);
    }

    AccountableString key = new AccountableString(filePath + IoTDBConstant.PATH_SEPARATOR
        + seriesPath.getDevice() + seriesPath.getMeasurement());

    cacheRequestNum.incrementAndGet();

    lock.readLock().lock();
    try {
      if (lruCache.containsKey(key)) {
        cacheHitNum.incrementAndGet();
        printCacheLog(true);
        return new ArrayList<>(lruCache.get(key));
      }
    } finally {
      lock.readLock().unlock();
    }

    lock.writeLock().lock();
    try {
      if (lruCache.containsKey(key)) {
        printCacheLog(true);
        cacheHitNum.incrementAndGet();
        return new ArrayList<>(lruCache.get(key));
      }
      printCacheLog(false);
      // bloom filter part
      TsFileSequenceReader tsFileReader = FileReaderManager.getInstance().get(filePath, true);
      BloomFilter bloomFilter = tsFileReader.readBloomFilter();
      if (bloomFilter != null && !bloomFilter.contains(seriesPath.getFullPath())) {
        return new ArrayList<>();
      }
      List<ChunkMetadata> chunkMetaDataList = FileLoaderUtils
          .getChunkMetadataList(seriesPath, filePath);
      lruCache.put(key, chunkMetaDataList);
      return new ArrayList<>(chunkMetaDataList);
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void printCacheLog(boolean isHit) {
    if (!logger.isDebugEnabled()) {
      return;
    }
    logger.debug(
        "[ChunkMetaData cache {}hit] The number of requests for cache is {}, hit rate is {}.",
        isHit ? "" : "didn't ", cacheRequestNum.get(),
        cacheHitNum.get() * 1.0 / cacheRequestNum.get());
  }

  double calculateChunkMetaDataHitRatio() {
    if (cacheRequestNum.get() != 0) {
      return cacheHitNum.get() * 1.0 / cacheRequestNum.get();
    } else {
      return 0;
    }
  }

  public long getUsedMemory() {
    return lruCache.getUsedMemory();
  }

  public long getMaxMemory() {
    return lruCache.getMaxMemory();
  }

  public double getUsedMemoryProportion() {
    return lruCache.getUsedMemoryProportion();
  }

  public long getAverageSize() {
    return lruCache.getAverageSize();
  }

  /**
   * clear LRUCache.
   */
  public void clear() {
    lock.writeLock().lock();
    if (lruCache != null) {
      lruCache.clear();
    }
    lock.writeLock().unlock();
  }

  public void remove(TsFileResource resource) {
    lock.writeLock().lock();
    if (resource != null) {
      lruCache.entrySet().removeIf(e -> e.getKey().getString().startsWith(resource.getPath()));
    }
    lock.writeLock().unlock();
  }

  /**
   * singleton pattern.
   */
  private static class ChunkMetadataCacheSingleton {

    private static final ChunkMetadataCache INSTANCE = new
        ChunkMetadataCache(MEMORY_THRESHOLD_IN_B);
  }
}