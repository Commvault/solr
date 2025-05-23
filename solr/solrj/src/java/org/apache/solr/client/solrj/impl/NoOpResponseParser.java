/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Set;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;

/**
 * A special parser that puts the entire response into a string "response" field in the NamedList.
 *
 * @deprecated see {@link InputStreamResponseParser instead}; more efficient
 */
@Deprecated
public class NoOpResponseParser extends ResponseParser {

  private final String writerType;

  public NoOpResponseParser(String writerType) {
    this.writerType = writerType;
  }

  @Override
  public final String getWriterType() {
    return writerType;
  }

  @Override
  public Collection<String> getContentTypes() {
    return Set.of();
  }

  public NamedList<Object> processResponse(Reader reader) {
    try {
      StringWriter writer = new StringWriter();
      reader.transferTo(writer);
      String output = writer.toString();
      NamedList<Object> list = new NamedList<>();
      list.add("response", output);
      return list;
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "parsing error", e);
    }
  }

  @Override
  public NamedList<Object> processResponse(InputStream body, String encoding) throws IOException {
    StringWriter writer = new StringWriter();
    new InputStreamReader(body, encoding == null ? "UTF-8" : encoding).transferTo(writer);
    String output = writer.toString();
    NamedList<Object> list = new NamedList<>();
    list.add("response", output);
    return list;
  }
}
