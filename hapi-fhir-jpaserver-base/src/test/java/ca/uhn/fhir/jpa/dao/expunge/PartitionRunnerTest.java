package ca.uhn.fhir.jpa.dao.expunge;

import ca.uhn.fhir.jpa.config.TestDstu3Config;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.model.interceptor.api.HookParams;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isOneOf;
import static org.junit.Assert.assertEquals;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {TestDstu3Config.class})
public class PartitionRunnerTest {
	private static final Logger ourLog = LoggerFactory.getLogger(PartitionRunnerTest.class);
	private static final String EXPUNGE_THREADNAME_1 = "expunge-1";
	private static final String EXPUNGE_THREADNAME_2 = "expunge-2";

	@Autowired
	private PartitionRunner myPartitionRunner;

	@Autowired
	private DaoConfig myDaoConfig;
	private PointcutLatch myLatch = new PointcutLatch("partition call");

	@After
	public void before() {
		myDaoConfig.setExpungeThreadCount(new DaoConfig().getExpungeThreadCount());
		myDaoConfig.setExpungeBatchSize(new DaoConfig().getExpungeBatchSize());
	}

	@Test
	public void emptyList() {
		Slice<Long> resourceIds = buildSlice(0);
		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(0);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		myLatch.clear();
	}

	private Slice<Long> buildSlice(int size) {
		List<Long> list = new ArrayList<>();
		for (long i = 0; i < size; ++i) {
			list.add(i + 1);
		}
		return new SliceImpl(list);
	}

	@Test
	public void oneItem() throws InterruptedException {
		Slice<Long> resourceIds = buildSlice(1);

		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(1);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(myLatch.awaitExpected());
		assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
		assertEquals(1, partitionCall.size);
	}


	@Test
	public void twoItems() throws InterruptedException {
		Slice<Long> resourceIds = buildSlice(2);

		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(1);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(myLatch.awaitExpected());
		assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
		assertEquals(2, partitionCall.size);
	}

	@Test
	public void tenItemsBatch5() throws InterruptedException {
		Slice<Long> resourceIds = buildSlice(10);
		myDaoConfig.setExpungeBatchSize(5);

		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(2);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		List<HookParams> calls = myLatch.awaitExpected();
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 0);
			assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
			assertEquals(5, partitionCall.size);
		}
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 1);
			assertEquals(EXPUNGE_THREADNAME_2, partitionCall.threadName);
			assertEquals(5, partitionCall.size);
		}
	}

	@Test
	public void nineItemsBatch5() throws InterruptedException {
		Slice<Long> resourceIds = buildSlice(9);
		myDaoConfig.setExpungeBatchSize(5);

		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(2);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		List<HookParams> calls = myLatch.awaitExpected();
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 0);
			assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
			assertEquals(5, partitionCall.size);
		}
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 1);
			assertEquals(EXPUNGE_THREADNAME_2, partitionCall.threadName);
			assertEquals(4, partitionCall.size);
		}
	}

	@Test
	public void tenItemsOneThread() throws InterruptedException {
		Slice<Long> resourceIds = buildSlice(10);
		myDaoConfig.setExpungeBatchSize(5);
		myDaoConfig.setExpungeThreadCount(1);

		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(2);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		List<HookParams> calls = myLatch.awaitExpected();
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 0);
			assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
			assertEquals(5, partitionCall.size);
		}
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 1);
			assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
			assertEquals(5, partitionCall.size);
		}
	}

	@Test
	public void elevenItemsTwoThreads() throws InterruptedException {
		Slice<Long> resourceIds = buildSlice(11);
		myDaoConfig.setExpungeBatchSize(4);
		myDaoConfig.setExpungeThreadCount(2);

		Consumer<List<Long>> partitionConsumer = buildPartitionConsumer(myLatch);
		myLatch.setExpectedCount(3);
		myPartitionRunner.runInPartitionedTransactionThreads(resourceIds, partitionConsumer);
		List<HookParams> calls = myLatch.awaitExpected();
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 0);
			assertEquals(EXPUNGE_THREADNAME_1, partitionCall.threadName);
			assertEquals(4, partitionCall.size);
		}
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 1);
			assertEquals(EXPUNGE_THREADNAME_2, partitionCall.threadName);
			assertEquals(4, partitionCall.size);
		}
		{
			PartitionCall partitionCall = (PartitionCall) PointcutLatch.getLatchInvocationParameter(calls, 2);
			assertThat( partitionCall.threadName, isOneOf(EXPUNGE_THREADNAME_1, EXPUNGE_THREADNAME_2));
			assertEquals(3, partitionCall.size);
		}
	}

	private Consumer<List<Long>> buildPartitionConsumer(PointcutLatch latch) {
		return list -> latch.call(new PartitionCall(Thread.currentThread().getName(), list.size()));
	}

	static class PartitionCall {
		private final String threadName;
		private final int size;

		PartitionCall(String theThreadName, int theSize) {
			threadName = theThreadName;
			size = theSize;
		}

		@Override
		public String toString() {
			return new ToStringBuilder(this)
				.append("myThreadName", threadName)
				.append("mySize", size)
				.toString();
		}
	}
}
