package com.avaje.tests.query;

import com.avaje.ebean.BaseTestCase;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.Query;
import com.avaje.ebean.QueryIterator;
import com.avaje.tests.model.basic.Customer;
import com.avaje.tests.model.basic.Order;
import com.avaje.tests.model.basic.ResetBasicData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestOneToManyCorrectGrouping extends BaseTestCase {

  public static final int EXPECTED_ITERATIONS = 2;

  @Test
  public void test() {

    ResetBasicData.reset();
    Query<Customer> customerQuery = Ebean.find(Customer.class)
        .fetch("orders")
        .where()
        .query();

    QueryIterator<Customer> customerQueryIterator = customerQuery.findIterate();

    try {
      int count = 0;
      while (customerQueryIterator.hasNext()) {
        Customer customer = customerQueryIterator.next();
        System.out.println(String.format("Customer id %s", customer.getId()));
        for (Order order : customer.getOrders()) {
          System.out.println(String.format("--> Order id %s", order.getId()));
        }
        count++;
      }
      assertEquals(EXPECTED_ITERATIONS, count);

    } finally {
      customerQueryIterator.close();
    }
  }
}