/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.SplitCompressionInputStream;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

/**
 * Treats keys as offset in file and value as line. 
 */
public class LineRecordReader implements RecordReader<LongWritable, Text> {
  private static final Log LOG
    = LogFactory.getLog(LineRecordReader.class.getName());

  private CompressionCodecFactory compressionCodecs = null;
  private long start;
  private long pos;
  private long end;
  private LineReader in;
  int maxLineLength;
  private Seekable filePosition;
  private CompressionCodec codec;
  private Decompressor decompressor;

  /**
   * A class that provides a line reader from an input stream.
   * @deprecated Use {@link org.apache.hadoop.util.LineReader} instead.
   */
  @Deprecated
  public static class LineReader extends org.apache.hadoop.util.LineReader {
    LineReader(InputStream in) {
      super(in);
    }
    LineReader(InputStream in, int bufferSize) {
      super(in, bufferSize);
    }
    public LineReader(InputStream in, Configuration conf) throws IOException {
      super(in, conf);
    }
  }

  public LineRecordReader(Configuration job, 
                          FileSplit split) throws IOException {
    this.maxLineLength = job.getInt("mapred.linerecordreader.maxlength",
                                    Integer.MAX_VALUE);
    start = split.getStart();
    end = start + split.getLength();

    String adjusted_path = null;
    String output = null, token1 = null, token2 = null, token3 = null;
    if (split.getPath().toString().contains("/var/local/osd")) { /* we are all set */
      adjusted_path = split.getPath().toString();
      if (end == 0) { /* size not assigned yet */
        int cut_from = adjusted_path.lastIndexOf("/");
        int cut_until = adjusted_path.indexOf("_", cut_from);
        String size_info_path = System.getProperty("user.home");
        size_info_path = size_info_path + "/global_file_SIZES";
        File global_size_info = new File (size_info_path);
        BufferedReader size_file_buffer = null;
        size_file_buffer = new BufferedReader(new FileReader(global_size_info));
        while ((output = size_file_buffer.readLine()) != null)
        {
          StringTokenizer st = new StringTokenizer(output);
          while (st.hasMoreTokens()) {
            token1 = st.nextToken();
            if (token1.equals(adjusted_path.substring(cut_from + 1, cut_until)))
            {
              token2 = st.nextToken();
              end = Integer.parseInt(token2);
              break;
            }
          }
        }
      }
    } else {
      String hostname = java.net.InetAddress.getLocalHost().getHostName();
      String combined_info_path = System.getProperty("user.home");
      combined_info_path = combined_info_path + "/global_file_COMBINED";
      File combined_info_file = new File (combined_info_path);
      BufferedReader combined_info_buffer = null;
      combined_info_buffer = new BufferedReader(new FileReader(combined_info_file));
      while ((output = combined_info_buffer.readLine()) != null)
      {
        StringTokenizer st = new StringTokenizer(output);
        token1 = st.nextToken();
        token2 = st.nextToken();
        token3 = st.nextToken();
        if(token3.contains(split.getPath().getName() + "_") && hostname.contains(token2))
        {
            adjusted_path = token3;
            break;
        }
      }
    }

    final Path file = split.getPath();
    compressionCodecs = new CompressionCodecFactory(job);
    codec = compressionCodecs.getCodec(file);

    // open the file and seek to the start of the split
    FileSystem fs = null;
    FSDataInputStream fileIn = null;
    InputStream adjustedIn = null;
    if (adjusted_path == null) {
      fs = file.getFileSystem(job);
      fileIn = fs.open(file);
    } else {
      adjustedIn = new FileInputStream(adjusted_path);
    }

    if (isCompressedInput()) {
      decompressor = CodecPool.getDecompressor(codec);
      if (codec instanceof SplittableCompressionCodec) {
        final SplitCompressionInputStream cIn =
          ((SplittableCompressionCodec)codec).createInputStream(
            fileIn, decompressor, start, end,
            SplittableCompressionCodec.READ_MODE.BYBLOCK);
        in = new LineReader(cIn, job);
        start = cIn.getAdjustedStart();
        end = cIn.getAdjustedEnd();
        filePosition = cIn; // take pos from compressed stream
      } else {
        in = new LineReader(codec.createInputStream(fileIn, decompressor), job);
        filePosition = fileIn;
      }
    } else {
      if (adjusted_path == null) {
        fileIn.seek(start);
        in = new LineReader(fileIn, job);
        filePosition = fileIn;
      } else {
        adjustedIn.skip(start);
        in = new LineReader(adjustedIn, job);
      }
    }
    // If this is not the first split, we always throw away first record
    // because we always (except the last split) read one extra line in
    // next() method.
    if (start != 0) {
      start += in.readLine(new Text(), 0, maxBytesToConsume(start));
    }
    this.pos = start;
  }

  private boolean isCompressedInput() {
    return (codec != null);
  }

  private int maxBytesToConsume(long pos) {
    return isCompressedInput()
      ? Integer.MAX_VALUE
      : (int) Math.min(Integer.MAX_VALUE, end - pos);
  }

  private long getFilePosition() throws IOException {
    long retVal;
    if (isCompressedInput() && null != filePosition) {
      retVal = filePosition.getPos();
    } else {
      retVal = pos;
    }
    return retVal;
  }

  public LineRecordReader(InputStream in, long offset, long endOffset,
                          int maxLineLength) {
    this.maxLineLength = maxLineLength;
    this.in = new LineReader(in);
    this.start = offset;
    this.pos = offset;
    this.end = endOffset;
    this.filePosition = null;
  }

  public LineRecordReader(InputStream in, long offset, long endOffset, 
                          Configuration job) 
    throws IOException{
    this.maxLineLength = job.getInt("mapred.linerecordreader.maxlength",
                                    Integer.MAX_VALUE);
    this.in = new LineReader(in, job);
    this.start = offset;
    this.pos = offset;
    this.end = endOffset;    
    this.filePosition = null;
  }
  
  public LongWritable createKey() {
    return new LongWritable();
  }
  
  public Text createValue() {
    return new Text();
  }
  
  /** Read a line. */
  public synchronized boolean next(LongWritable key, Text value)
    throws IOException {

    // We always read one extra line, which lies outside the upper
    // split limit i.e. (end - 1)
    while (getFilePosition() <= end) {
      key.set(pos);

      int newSize = in.readLine(value, maxLineLength,
          Math.max(maxBytesToConsume(pos), maxLineLength));
      if (newSize == 0) {
        return false;
      }
      pos += newSize;
      if (newSize < maxLineLength) {
        return true;
      }

      // line too long. try again
      LOG.info("Skipped line of size " + newSize + " at pos " + (pos - newSize));
    }

    return false;
  }

  /**
   * Get the progress within the split
   */
  public float getProgress() throws IOException {
    if (start == end) {
      return 0.0f;
    } else {
      return Math.min(1.0f,
        (getFilePosition() - start) / (float)(end - start));
    }
  }
  
  public synchronized long getPos() throws IOException {
    return pos;
  }

  public synchronized void close() throws IOException {
    try {
      if (in != null) {
        in.close();
      }
    } finally {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
      }
    }
  }
}
