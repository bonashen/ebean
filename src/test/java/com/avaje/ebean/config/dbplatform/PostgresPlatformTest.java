package com.avaje.ebean.config.dbplatform;

import com.avaje.ebean.config.ServerConfig;
import com.avaje.ebean.dbmigration.ddlgeneration.platform.PlatformDdl;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PostgresPlatformTest {



  @Test
  public void testTypeConversion() {

    PostgresPlatform platform = new PostgresPlatform();
    PlatformDdl ddl = platform.getPlatformDdl();

    assertThat(ddl.convert("clob", false)).isEqualTo("text");
    assertThat(ddl.convert("blob", false)).isEqualTo("bytea");
    assertThat(ddl.convert("json", false)).isEqualTo("json");
    assertThat(ddl.convert("jsonb", false)).isEqualTo("jsonb");
    assertThat(ddl.convert("hstore", false)).isEqualTo("hstore");
    assertThat(ddl.convert("double", false)).isEqualTo("float");
    assertThat(ddl.convert("tinyint", false)).isEqualTo("smallint");
    assertThat(ddl.convert("double", false)).isEqualTo("float");
    assertThat(ddl.convert("varchar(20)", false)).isEqualTo("varchar(20)");
    assertThat(ddl.convert("decimal(10)", false)).isEqualTo("decimal(10)");
    assertThat(ddl.convert("decimal(8,4)", false)).isEqualTo("decimal(8,4)");
    assertThat(ddl.convert("boolean", false)).isEqualTo("boolean");
    assertThat(ddl.convert("bit", false)).isEqualTo("bit");

  }

  @Test
  public void testUuidType() {

    PostgresPlatform platform = new PostgresPlatform();
    platform.configure(new ServerConfig());

    DbPlatformType dbType = platform.getDbTypeMap().get(DbPlatformType.UUID);
    String columnDefn = dbType.renderType(0, 0);

    assertThat(columnDefn).isEqualTo("uuid");
  }

}