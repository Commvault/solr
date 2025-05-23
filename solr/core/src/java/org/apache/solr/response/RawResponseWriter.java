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
package org.apache.solr.response;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;

/**
 * Writes a ContentStream directly to the output.
 *
 * <p>This writer is a special case that extends and alters the QueryResponseWriter contract. If
 * SolrQueryResponse contains a ContentStream added with the key {@link #CONTENT} then this writer
 * will output that stream exactly as is (with its Content-Type). if no such ContentStream has been
 * added, then a "base" QueryResponseWriter will be used to write the response according to the
 * usual contract. The name of the "base" writer can be specified as an initialization param for
 * this writer, or it defaults to the "standard" writer.
 *
 * @since solr 1.3
 */
public class RawResponseWriter implements QueryResponseWriter {

  public static final String CONTENT_TYPE = "application/vnd.apache.solr.raw";

  /**
   * The key that should be used to add a ContentStream to the SolrQueryResponse if you intend to
   * use this Writer.
   */
  public static final String CONTENT = "content";

  private String _baseWriter = null;

  /**
   * A fallback writer used for requests that don't return raw content and that aren't associated
   * with a particular SolrCore
   *
   * <p>Populated upon first use.
   */
  private QueryResponseWriter defaultWriter;

  @Override
  public void init(NamedList<?> n) {
    if (n != null) {
      Object base = n.get("base");
      if (base != null) {
        _baseWriter = base.toString();
      }
    }
  }

  protected QueryResponseWriter getBaseWriter(SolrQueryRequest request) {
    if (request.getCore() != null) {
      return request.getCore().getQueryResponseWriter(_baseWriter);
    }

    // Requests to a specific core already have writers, but we still need a 'default writer' for
    // non-core (i.e. container-level) APIs
    synchronized (this) {
      if (defaultWriter == null) {
        defaultWriter = new JSONResponseWriter();
      }
    }
    return defaultWriter;
  }

  @Override
  public String getContentType(SolrQueryRequest request, SolrQueryResponse response) {
    Object obj = response.getValues().get(CONTENT);
    if (obj instanceof ContentStream content) {
      return content.getContentType();
    }
    return getBaseWriter(request).getContentType(request, response);
  }

  @Override
  public void write(
      OutputStream out, SolrQueryRequest request, SolrQueryResponse response, String contentType)
      throws IOException {
    Object obj = response.getValues().get(CONTENT);
    if (obj instanceof ContentStream content) {
      // copy the contents to the writer...
      try (InputStream in = content.getStream()) {
        in.transferTo(out);
      }
    } else if (obj instanceof SolrCore.RawWriter rawWriter) {
      rawWriter.write(out);
      if (rawWriter instanceof Closeable closeable) closeable.close();
    } else {
      getBaseWriter(request).write(out, request, response, contentType);
    }
  }
}
