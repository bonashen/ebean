package com.avaje.tests.cache;

import com.avaje.ebean.BaseTestCase;
import com.avaje.ebean.Ebean;
import com.avaje.tests.model.basic.OCachedBean;
import org.avaje.ebeantest.LoggedSqlCollector;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;

/**
 * Test class testing deleting/invalidating of cached beans
 */
public class TestBeanCache extends BaseTestCase {

  @Test
  public void findById_when_idTypeConverted() {

    OCachedBean bean = new OCachedBean();
    bean.setName("findById");
    Ebean.save(bean);

    OCachedBean bean0 = Ebean.find(OCachedBean.class, bean.getId());
    assertNotNull(bean0);

    // expect to hit the cache, no SQL
    LoggedSqlCollector.start();
    OCachedBean bean1 = Ebean.find(OCachedBean.class, bean.getId());
    List<String> sql = LoggedSqlCollector.stop();
    assertNotNull(bean1);
    assertThat(sql).isEmpty();

    // expect to hit the cache, no SQL
    LoggedSqlCollector.start();
    OCachedBean bean2 = Ebean.find(OCachedBean.class).setReadOnly(true).setId(String.valueOf(bean.getId())).findUnique();
    sql = LoggedSqlCollector.stop();
    assertNotNull(bean2);
    assertThat(sql).isEmpty();

  }
}
