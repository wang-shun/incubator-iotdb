/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.engine.storagegroup;

import org.apache.commons.io.FileUtils;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.querycontext.ReadOnlyMemChunk;
import org.apache.iotdb.db.engine.upgrade.UpgradeTask;
import org.apache.iotdb.db.service.UpgradeSevice;
import org.apache.iotdb.db.utils.UpgradeUtils;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.fileSystem.fsFactory.FSFactory;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TsFileResource {

  private static final Logger logger = LoggerFactory.getLogger(TsFileResource.class);

  // tsfile
  private File file;

  public static final String RESOURCE_SUFFIX = ".resource";
  static final String TEMP_SUFFIX = ".temp";
  private static final String CLOSING_SUFFIX = ".closing";

  /**
   * device -> start time
   */
  protected Map<String, Long> startTimeMap;

  /**
   * device -> end time. It is null if it's an unsealed sequence tsfile
   */
  protected Map<String, Long> endTimeMap;

  public TsFileProcessor getProcessor() {
    return processor;
  }

  private TsFileProcessor processor;

  private ModificationFile modFile;

  private volatile boolean closed = false;
  private volatile boolean deleted = false;
  private volatile boolean isMerging = false;

  // historicalVersions are used to track the merge history of a TsFile. For a TsFile generated
  // by flush, this field only contains its own version number. For a TsFile generated by merge,
  // its historicalVersions are the union of all TsFiles' historicalVersions that joined this merge.
  // This field helps us compare the files that are generated by different IoTDBs that share the
  // same file generation policy but have their own merge policies.
  private Set<Long> historicalVersions;

  /**
   * Chunk metadata list of unsealed tsfile. Only be set in a temporal TsFileResource in a query
   * process.
   */
  private List<ChunkMetadata> chunkMetadataList;

  /**
   * Mem chunk data. Only be set in a temporal TsFileResource in a query process.
   */
  private List<ReadOnlyMemChunk> readOnlyMemChunk;

  /**
   * used for unsealed file to get TimeseriesMetadata
   */
  private TimeseriesMetadata timeSeriesMetadata;

  private ReentrantReadWriteLock writeQueryLock = new ReentrantReadWriteLock();

  private FSFactory fsFactory = FSFactoryProducer.getFSFactory();

  /**
   * set to false if we failed to get TimeSeriesMetadata
   */
  private boolean canGetTimeSeriesMetadata = true;

  public TsFileResource() {
  }

  public TsFileResource(TsFileResource other) throws IOException {
    this.file = other.file;
    this.startTimeMap = other.startTimeMap;
    this.endTimeMap = other.endTimeMap;
    this.processor = other.processor;
    this.modFile = other.modFile;
    this.closed = other.closed;
    this.deleted = other.deleted;
    this.isMerging = other.isMerging;
    this.chunkMetadataList = other.chunkMetadataList;
    this.readOnlyMemChunk = other.readOnlyMemChunk;
    generateTimeSeriesMetadata();
    this.writeQueryLock = other.writeQueryLock;
    this.fsFactory = other.fsFactory;
    this.historicalVersions = other.historicalVersions;
  }

  /**
   * for sealed TsFile, call setClosed to close TsFileResource
   */
  public TsFileResource(File file) {
    this.file = file;
    this.startTimeMap = new ConcurrentHashMap<>();
    this.endTimeMap = new HashMap<>();
  }

  /**
   * unsealed TsFile
   */
  public TsFileResource(File file, TsFileProcessor processor) {
    this.file = file;
    this.startTimeMap = new ConcurrentHashMap<>();
    this.endTimeMap = new ConcurrentHashMap<>();
    this.processor = processor;
  }

  /**
   * unsealed TsFile
   */
  public TsFileResource(File file,
      Map<String, Long> startTimeMap,
      Map<String, Long> endTimeMap,
      List<ReadOnlyMemChunk> readOnlyMemChunk,
      List<ChunkMetadata> chunkMetadataList) throws IOException {
    this.file = file;
    this.startTimeMap = startTimeMap;
    this.endTimeMap = endTimeMap;
    this.chunkMetadataList = chunkMetadataList;
    this.readOnlyMemChunk = readOnlyMemChunk;
    generateTimeSeriesMetadata();
  }

  private void generateTimeSeriesMetadata() throws IOException {
    if (chunkMetadataList.isEmpty() && readOnlyMemChunk.isEmpty()) {
      timeSeriesMetadata = null;
    }
    timeSeriesMetadata = new TimeseriesMetadata();
    timeSeriesMetadata.setOffsetOfChunkMetaDataList(-1);
    timeSeriesMetadata.setDataSizeOfChunkMetaDataList(-1);

    if (!chunkMetadataList.isEmpty()) {
      timeSeriesMetadata.setMeasurementId(chunkMetadataList.get(0).getMeasurementUid());
      TSDataType dataType = chunkMetadataList.get(0).getDataType();
      timeSeriesMetadata.setTSDataType(dataType);
    } else if (!readOnlyMemChunk.isEmpty()) {
      timeSeriesMetadata.setMeasurementId(readOnlyMemChunk.get(0).getMeasurementUid());
      TSDataType dataType = readOnlyMemChunk.get(0).getDataType();
      timeSeriesMetadata.setTSDataType(dataType);
    }
    if (timeSeriesMetadata.getTSDataType() != null) {
      Statistics seriesStatistics = Statistics.getStatsByType(timeSeriesMetadata.getTSDataType());
      // flush chunkMetadataList one by one
      for (ChunkMetadata chunkMetadata : chunkMetadataList) {
        seriesStatistics.mergeStatistics(chunkMetadata.getStatistics());
      }

      for (ReadOnlyMemChunk memChunk : readOnlyMemChunk) {
        if (!memChunk.isEmpty()) {
          seriesStatistics.mergeStatistics(memChunk.getChunkMetaData().getStatistics());
        }
      }
      timeSeriesMetadata.setStatistics(seriesStatistics);
    } else {
      timeSeriesMetadata = null;
    }

  }

  public void serialize() throws IOException {
    try (OutputStream outputStream = fsFactory.getBufferedOutputStream(
        file + RESOURCE_SUFFIX + TEMP_SUFFIX)) {
      ReadWriteIOUtils.write(this.startTimeMap.size(), outputStream);
      for (Entry<String, Long> entry : this.startTimeMap.entrySet()) {
        ReadWriteIOUtils.write(entry.getKey(), outputStream);
        ReadWriteIOUtils.write(entry.getValue(), outputStream);
      }
      ReadWriteIOUtils.write(this.endTimeMap.size(), outputStream);
      for (Entry<String, Long> entry : this.endTimeMap.entrySet()) {
        ReadWriteIOUtils.write(entry.getKey(), outputStream);
        ReadWriteIOUtils.write(entry.getValue(), outputStream);
      }

      if (historicalVersions != null) {
        ReadWriteIOUtils.write(this.historicalVersions.size(), outputStream);
        for (Long historicalVersion : historicalVersions) {
          ReadWriteIOUtils.write(historicalVersion, outputStream);
        }
      }
    }
    File src = fsFactory.getFile(file + RESOURCE_SUFFIX + TEMP_SUFFIX);
    File dest = fsFactory.getFile(file + RESOURCE_SUFFIX);
    dest.delete();
    fsFactory.moveFile(src, dest);
  }

  public void deserialize() throws IOException {
    try (InputStream inputStream = fsFactory.getBufferedInputStream(
        file + RESOURCE_SUFFIX)) {
      int size = ReadWriteIOUtils.readInt(inputStream);
      Map<String, Long> startTimes = new HashMap<>();
      for (int i = 0; i < size; i++) {
        String path = ReadWriteIOUtils.readString(inputStream);
        long time = ReadWriteIOUtils.readLong(inputStream);
        startTimes.put(path, time);
      }
      size = ReadWriteIOUtils.readInt(inputStream);
      Map<String, Long> endTimes = new HashMap<>();
      for (int i = 0; i < size; i++) {
        String path = ReadWriteIOUtils.readString(inputStream);
        long time = ReadWriteIOUtils.readLong(inputStream);
        endTimes.put(path, time);
      }
      this.startTimeMap = startTimes;
      this.endTimeMap = endTimes;

      if (inputStream.available() > 0) {
        int versionSize = ReadWriteIOUtils.readInt(inputStream);
        historicalVersions = new HashSet<>();
        for (int i = 0; i < versionSize; i++) {
          historicalVersions.add(ReadWriteIOUtils.readLong(inputStream));
        }
      } else {
        // use the version in file name as the historical version for files of old versions
        long version = Long.parseLong(file.getName().split(IoTDBConstant.TSFILE_NAME_SEPARATOR)[1]);
        historicalVersions = Collections.singleton(version);
      }
    }
  }

  public void updateStartTime(String device, long time) {
    long startTime = startTimeMap.getOrDefault(device, Long.MAX_VALUE);
    if (time < startTime) {
      startTimeMap.put(device, time);
    }
  }

  public void updateEndTime(String device, long time) {
    long endTime = endTimeMap.getOrDefault(device, Long.MIN_VALUE);
    if (time > endTime) {
      endTimeMap.put(device, time);
    }
  }

  public boolean fileExists() {
    return fsFactory.getFile(file + RESOURCE_SUFFIX).exists();
  }

  void forceUpdateEndTime(String device, long time) {
    endTimeMap.put(device, time);
  }

  public List<ChunkMetadata> getChunkMetadataList() {
    return chunkMetadataList;
  }

  public List<ReadOnlyMemChunk> getReadOnlyMemChunk() {
    return readOnlyMemChunk;
  }

  public synchronized ModificationFile getModFile() {
    if (modFile == null) {
      modFile = new ModificationFile(file.getAbsolutePath() + ModificationFile.FILE_SUFFIX);
    }
    return modFile;
  }

  public void setFile(File file) {
    this.file = file;
  }

  boolean containsDevice(String deviceId) {
    return startTimeMap.containsKey(deviceId);
  }

  public File getFile() {
    return file;
  }

  public String getPath() {
    return file.getPath();
  }

  public long getFileSize() {
    return file.length();
  }

  public Map<String, Long> getStartTimeMap() {
    return startTimeMap;
  }

  public Map<String, Long> getEndTimeMap() {
    return endTimeMap;
  }

  public boolean isClosed() {
    return closed;
  }

  public void close() throws IOException {
    closed = true;
    if (modFile != null) {
      modFile.close();
      modFile = null;
    }
    processor = null;
    chunkMetadataList = null;
  }

  TsFileProcessor getUnsealedFileProcessor() {
    return processor;
  }

  public ReentrantReadWriteLock getWriteQueryLock() {
    return writeQueryLock;
  }

  void doUpgrade() {
    if (UpgradeUtils.isNeedUpgrade(this)) {
      UpgradeSevice.getINSTANCE().submitUpgradeTask(new UpgradeTask(this));
    }
  }

  public void removeModFile() throws IOException {
    getModFile().remove();
    modFile = null;
  }

  public void remove() {
    file.delete();
    fsFactory.getFile(file.getPath() + RESOURCE_SUFFIX).delete();
    fsFactory.getFile(file.getPath() + ModificationFile.FILE_SUFFIX).delete();
  }

  void moveTo(File targetDir) throws IOException {
    FileUtils.moveFile(file, new File(targetDir, file.getName()));
    FileUtils.moveFile(fsFactory.getFile(file.getPath() + RESOURCE_SUFFIX),
        new File(targetDir, file.getName() + RESOURCE_SUFFIX));
    fsFactory.getFile(file.getPath() + ModificationFile.FILE_SUFFIX).delete();
  }

  @Override
  public String toString() {
    return file.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TsFileResource that = (TsFileResource) o;
    return Objects.equals(file, that.file);
  }

  @Override
  public int hashCode() {
    return Objects.hash(file);
  }

  public void setClosed(boolean closed) {
    this.closed = closed;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public void setDeleted(boolean deleted) {
    this.deleted = deleted;
  }

  boolean isMerging() {
    return isMerging;
  }

  public void setMerging(boolean merging) {
    isMerging = merging;
  }

  /**
   * check if any of the device lives over the given time bound
   *
   * @param timeLowerBound
   */
  public boolean stillLives(long timeLowerBound) {
    if (timeLowerBound == Long.MAX_VALUE) {
      return true;
    }
    for (long endTime : endTimeMap.values()) {
      // the file cannot be deleted if any device still lives
      if (endTime >= timeLowerBound) {
        return true;
      }
    }
    return false;
  }

  protected void setStartTimeMap(Map<String, Long> startTimeMap) {
    this.startTimeMap = startTimeMap;
  }

  protected void setEndTimeMap(Map<String, Long> endTimeMap) {
    this.endTimeMap = endTimeMap;
  }

  /**
   * set a file flag indicating that the file is being closed, so during recovery we could know we
   * should close the file.
   */
  void setCloseFlag() {
    try {
      new File(file.getAbsoluteFile() + CLOSING_SUFFIX).createNewFile();
    } catch (IOException e) {
      logger.error("Cannot create close flag for {}", file, e);
    }
  }

  /**
   * clean the close flag (if existed) when the file is successfully closed.
   */
  public void cleanCloseFlag() {
    new File(file.getAbsoluteFile() + CLOSING_SUFFIX).delete();
  }

  public boolean isCloseFlagSet() {
    return new File(file.getAbsoluteFile() + CLOSING_SUFFIX).exists();
  }

  public Set<Long> getHistoricalVersions() {
    return historicalVersions;
  }

  public void setHistoricalVersions(Set<Long> historicalVersions) {
    this.historicalVersions = historicalVersions;
  }

  public void setProcessor(TsFileProcessor processor) {
    this.processor = processor;
  }

  public boolean canGetTimeSeriesMetadata() {
    return canGetTimeSeriesMetadata;
  }

  public void setCanGetTimeSeriesMetadata(boolean canGetTimeSeriesMetadata) {
    this.canGetTimeSeriesMetadata = canGetTimeSeriesMetadata;
  }

  public TimeseriesMetadata getTimeSeriesMetadata() {
    return timeSeriesMetadata;
  }
}
