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
package org.apache.solr.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.apache.lucene.tests.mockfile.FilterPath;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.AbstractBadConfigTestBase;
import org.junit.After;
import org.junit.Before;
import org.locationtech.spatial4j.shape.Shape;

public class SpatialRPTFieldTypeTest extends AbstractBadConfigTestBase {

  private static Path tmpSolrHome;
  private static Path tmpConfDir;

  private static final String collection = "collection1";
  private static final String confDir = collection + "/conf";

  @Before
  public void initManagedSchemaCore() throws Exception {
    tmpSolrHome = createTempDir();
    tmpConfDir = FilterPath.unwrap(tmpSolrHome.resolve(confDir));
    Path testHomeConfDir = TEST_HOME().resolve(confDir);
    Files.createDirectories(tmpConfDir);
    PathUtils.copyFileToDirectory(
        testHomeConfDir.resolve("solrconfig-managed-schema.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(testHomeConfDir.resolve("solrconfig-basic.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(
        testHomeConfDir.resolve("solrconfig.snippet.randomindexconfig.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(
        testHomeConfDir.resolve("schema-one-field-no-dynamic-field.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(
        testHomeConfDir.resolve("schema-one-field-no-dynamic-field-unique-key.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(testHomeConfDir.resolve("schema-minimal.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(testHomeConfDir.resolve("schema_codec.xml"), tmpConfDir);
    PathUtils.copyFileToDirectory(testHomeConfDir.resolve("schema-bm25.xml"), tmpConfDir);

    // initCore will trigger an upgrade to managed schema, since the solrconfig has
    // <schemaFactory class="ManagedIndexSchemaFactory" ... />
    System.setProperty("managed.schema.mutable", "false");
    System.setProperty("enable.update.log", "false");
    initCore("solrconfig-managed-schema.xml", "schema-minimal.xml", tmpSolrHome);
  }

  @After
  public void afterClass() {
    deleteCore();
    System.clearProperty("managed.schema.mutable");
    System.clearProperty("enable.update.log");
  }

  static final String INDEXED_COORDINATES = "25,82";
  static final String QUERY_COORDINATES = "24,81";
  static final String DISTANCE_DEGREES = "1.3520328";
  static final String DISTANCE_KILOMETERS = "150.33939";
  static final String DISTANCE_MILES = "93.416565";

  public void testDistanceUnitsDegrees() throws Exception {
    setupRPTField("degrees");

    assertU(adoc("str", "X", "geo", INDEXED_COORDINATES));
    assertU(commit());
    String q;

    q = "geo:{!geofilt score=distance filter=false sfield=geo pt=" + QUERY_COORDINATES + " d=180}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_DEGREES + "']");

    q = "geo:{!geofilt score=degrees filter=false sfield=geo pt=" + QUERY_COORDINATES + " d=180}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_DEGREES + "']");

    q =
        "geo:{!geofilt score=kilometers filter=false sfield=geo pt="
            + QUERY_COORDINATES
            + " d=180}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_KILOMETERS + "']");

    q = "geo:{!geofilt score=miles filter=false sfield=geo pt=" + QUERY_COORDINATES + " d=180}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_MILES + "']");
  }

  public void testDistanceUnitsKilometers() throws Exception {
    setupRPTField("kilometers");

    assertU(adoc("str", "X", "geo", INDEXED_COORDINATES));
    assertU(commit());
    String q;

    q = "geo:{!geofilt score=distance filter=false sfield=geo pt=" + QUERY_COORDINATES + " d=1000}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_KILOMETERS + "']");

    q = "geo:{!geofilt score=degrees filter=false sfield=geo pt=" + QUERY_COORDINATES + " d=1000}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_DEGREES + "']");

    q =
        "geo:{!geofilt score=kilometers filter=false sfield=geo pt="
            + QUERY_COORDINATES
            + " d=1000}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_KILOMETERS + "']");

    q = "geo:{!geofilt score=miles filter=false sfield=geo pt=" + QUERY_COORDINATES + " d=1000}";
    assertQ(
        req("q", q, "fl", "*,score"),
        "//result/doc/float[@name='score'][.='" + DISTANCE_MILES + "']");
  }

  public void testJunkValuesForDistanceUnits() {
    Exception ex = expectThrows(Exception.class, () -> setupRPTField("rose"));
    assertTrue(ex.getMessage().startsWith("Must specify distanceUnits as one of"));
  }

  public void testMaxDistErrConversion() throws Exception {
    deleteCore();
    Path managedSchemaFile = tmpConfDir.resolve("managed-schema.xml");
    // Delete managed-schema.xml, so it won't block parsing a new schema
    Files.delete(managedSchemaFile);
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome);

    String fieldName = "new_text_field";
    assertNull(
        "Field '" + fieldName + "' is present in the schema",
        h.getCore().getLatestSchema().getFieldOrNull(fieldName));

    IndexSchema oldSchema = h.getCore().getLatestSchema();

    SpatialRecursivePrefixTreeFieldType rptFieldType = new SpatialRecursivePrefixTreeFieldType();
    Map<String, String> rptMap = new HashMap<>();

    rptFieldType.setTypeName("location_rpt");
    rptMap.put("geo", "true");

    // test km
    rptMap.put("distanceUnits", "kilometers");
    rptMap.put("maxDistErr", "0.001"); // 1 meter
    rptFieldType.init(oldSchema, rptMap);
    assertEquals(11, rptFieldType.grid.getMaxLevels());

    // test miles
    rptMap.put("distanceUnits", "miles");
    rptMap.put("maxDistErr", "0.001");
    rptFieldType.init(oldSchema, rptMap);
    assertEquals(10, rptFieldType.grid.getMaxLevels());

    // test degrees
    rptMap.put("distanceUnits", "degrees");
    rptMap.put("maxDistErr", "0.001");
    rptFieldType.init(oldSchema, rptMap);
    assertEquals(8, rptFieldType.grid.getMaxLevels());
  }

  public void testGeoDistanceFunctionWithBackCompat() throws Exception {
    setupRPTField(null);

    assertU(adoc("str", "X", "geo", "1,2"));
    assertU(commit());

    // geodist() should return in km
    assertJQ(
        req("defType", "func", "q", "geodist(3,4)", "sfield", "geo", "fl", "score"),
        1e-5,
        "/response/docs/[0]/score==314.4033");
  }

  public void testGeoDistanceFunctionWithKilometers() throws Exception {
    setupRPTField("kilometers");

    assertU(adoc("str", "X", "geo", "1,2"));
    assertU(commit());

    assertJQ(
        req("defType", "func", "q", "geodist(3,4)", "sfield", "geo", "fl", "score"),
        1e-5,
        "/response/docs/[0]/score==314.4033");
  }

  public void testGeoDistanceFunctionWithMiles() throws Exception {
    setupRPTField("miles");

    assertU(adoc("str", "X", "geo", "1,2"));
    assertU(commit());

    assertJQ(
        req("defType", "func", "q", "geodist(3,4)", "sfield", "geo", "fl", "score"),
        1e-5,
        "/response/docs/[0]/score==195.36115");
  }

  public void testShapeToFromStringWKT() throws Exception {
    setupRPTField(
        "miles",
        "WKT",
        random().nextBoolean()
            ? new SpatialRecursivePrefixTreeFieldType()
            : new RptWithGeometrySpatialField());

    AbstractSpatialFieldType<?> ftype =
        (AbstractSpatialFieldType<?>) h.getCore().getLatestSchema().getField("geo").getType();

    String wkt = "POINT (1 2)";
    Shape shape = ftype.parseShape(wkt);
    String out = ftype.shapeToString(shape);

    assertEquals(wkt, out);

    // assert fails GeoJSON
    expectThrows(
        SolrException.class, () -> ftype.parseShape("{\"type\":\"Point\",\"coordinates\":[1,2]}"));
  }

  public void testShapeToFromStringGeoJSON() throws Exception {
    setupRPTField(
        "miles",
        "GeoJSON",
        random().nextBoolean()
            ? new SpatialRecursivePrefixTreeFieldType()
            : new RptWithGeometrySpatialField());

    AbstractSpatialFieldType<?> ftype =
        (AbstractSpatialFieldType<?>) h.getCore().getLatestSchema().getField("geo").getType();

    String json = "{\"type\":\"Point\",\"coordinates\":[1,2]}";
    Shape shape = ftype.parseShape(json);
    String out = ftype.shapeToString(shape);

    assertEquals(json, out);
  }

  private void setupRPTField(String distanceUnits, String format, FieldType fieldType)
      throws Exception {
    deleteCore();
    Path managedSchemaFile = tmpConfDir.resolve("managed-schema.xml");
    // Delete managed-schema.xml, so it won't block parsing a new schema
    Files.delete(managedSchemaFile);
    System.setProperty("managed.schema.mutable", "true");
    initCore("solrconfig-managed-schema.xml", "schema-one-field-no-dynamic-field.xml", tmpSolrHome);

    String fieldName = "new_text_field";
    assertNull(
        "Field '" + fieldName + "' is present in the schema",
        h.getCore().getLatestSchema().getFieldOrNull(fieldName));

    IndexSchema oldSchema = h.getCore().getLatestSchema();

    if (fieldType == null) {
      fieldType = new SpatialRecursivePrefixTreeFieldType();
    }
    Map<String, String> rptMap = new HashMap<>();
    if (distanceUnits != null) rptMap.put("distanceUnits", distanceUnits);
    rptMap.put("geo", "true");
    if (format != null) {
      rptMap.put("format", format);
    }
    if (random().nextBoolean()) {
      // use Geo3D sometimes
      rptMap.put("spatialContextFactory", "Geo3D");
    }
    fieldType.init(oldSchema, rptMap);
    fieldType.setTypeName("location_rpt");
    SchemaField newField =
        new SchemaField(
            "geo",
            fieldType,
            SchemaField.STORED
                | SchemaField.INDEXED
                | SchemaField.OMIT_NORMS
                | SchemaField.OMIT_TF_POSITIONS,
            null);
    IndexSchema newSchema = oldSchema.addField(newField);

    h.getCore().setLatestSchema(newSchema);

    assertU(delQ("*:*"));
  }

  private void setupRPTField(String distanceUnits) throws Exception {
    setupRPTField(distanceUnits, null, null);
  }
}
