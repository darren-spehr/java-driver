package com.datastax.driver.core;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.driver.core.policies.DelegatingLoadBalancingPolicy;
import com.datastax.driver.core.policies.LoadBalancingPolicy;
import com.datastax.driver.core.policies.Policies;
import com.datastax.driver.core.policies.ReconnectionPolicy;

public class ControlConnectionTest {
    @Test(groups = "short")
    public void should_prevent_simultaneous_reconnection_attempts() throws InterruptedException {
        CCMBridge ccm = null;
        Cluster cluster = null;

        // Custom load balancing policy that counts the number of calls to newQueryPlan().
        // Since we don't open any session from our Cluster, only the control connection reattempts are calling this
        // method, therefore the invocation count is equal to the number of attempts.
        QueryPlanCountingPolicy loadBalancingPolicy = new QueryPlanCountingPolicy(Policies.defaultLoadBalancingPolicy());
        AtomicInteger reconnectionAttempts = loadBalancingPolicy.counter;

        // Custom reconnection policy with a very large delay (longer than the test duration), to make sure we count
        // only the first reconnection attempt of each reconnection handler.
        ReconnectionPolicy reconnectionPolicy = new ReconnectionPolicy() {
            @Override
            public ReconnectionSchedule newSchedule() {
                return new ReconnectionSchedule() {
                    @Override
                    public long nextDelayMs() {
                        return 60 * 1000;
                    }
                };
            }
        };

        try {
            ccm = CCMBridge.create("test", 2);
            // We pass only the first host as contact point, so we know the control connection will be on this host
            cluster = Cluster.builder()
                .addContactPoint(CCMBridge.ipOfNode(1))
                .withReconnectionPolicy(reconnectionPolicy)
                .withLoadBalancingPolicy(loadBalancingPolicy)
                .build();
            cluster.init();

            // Kill the control connection host, there should be exactly one reconnection attempt
            ccm.stop(1);
            TimeUnit.SECONDS.sleep(1); // Sleep for a while to make sure our final count is not the result of lucky timing
            assertThat(reconnectionAttempts.get()).isEqualTo(1);

            ccm.stop(2);
            TimeUnit.SECONDS.sleep(1);
            assertThat(reconnectionAttempts.get()).isEqualTo(2);

        } finally {
            if (cluster != null)
                cluster.close();
            if (ccm != null)
                ccm.remove();
        }
    }

    /**
     * Test for JAVA-509: UDT definitions were not properly parsed when using the default protocol version.
     *
     * This did not appear with other tests because the UDT needs to already exist when the driver initializes.
     * Therefore we use two different driver instances in this test.
     */
    @Test(groups = "short")
    public void should_parse_UDT_definitions_when_using_default_protocol_version() {
        TestUtils.versionCheck(2.1, 0, "This will only work with C* 2.1.0");

        CCMBridge ccm = null;
        Cluster cluster = null;

        try {
            ccm = CCMBridge.create("test", 1);

            // First driver instance: create UDT
            cluster = Cluster.builder().addContactPoint(CCMBridge.ipOfNode(1)).build();
            Session session = cluster.connect();
            session.execute("create keyspace ks WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
            session.execute("create type ks.foo (i int)");
            cluster.close();

            // Second driver instance: read UDT definition
            cluster = Cluster.builder().addContactPoint(CCMBridge.ipOfNode(1)).build();
            UserType fooType = cluster.getMetadata().getKeyspace("ks").getUserType("foo");

            assertThat(fooType.getFieldNames()).containsExactly("i");
        } finally {
            if (cluster != null)
                cluster.close();
            if (ccm != null)
                ccm.remove();
        }
    }

   static class QueryPlanCountingPolicy extends DelegatingLoadBalancingPolicy {

        final AtomicInteger counter = new AtomicInteger();

        public QueryPlanCountingPolicy(LoadBalancingPolicy delegate) {
            super(delegate);
        }

        public Iterator<Host> newQueryPlan(String loggedKeyspace, Statement statement) {
            counter.incrementAndGet();
            return super.newQueryPlan(loggedKeyspace, statement);
        }
    }
}
