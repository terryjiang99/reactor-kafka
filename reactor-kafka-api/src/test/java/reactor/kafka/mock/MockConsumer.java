/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package reactor.kafka.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidOffsetException;
import org.apache.kafka.common.record.TimestampType;

import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.internals.ConsumerFactory;

public class MockConsumer implements Consumer<Integer, String> {

    private static final long MAX_POLL_RECORDS = 2;

    private final ScheduledExecutorService executor;
    private final ReentrantLock consumerLock;
    private final Set<TopicPartition> assignment;
    private final Set<String> subscription;
    private final Set<TopicPartition> paused;
    private final Map<TopicPartition, Long> offsets;
    private final Queue<KafkaException> pollExceptions;
    private final Queue<KafkaException> commitExceptions;
    private final MockCluster cluster;
    private final boolean autoHeartbeat;
    private Duration sessionTimeout;
    private long sessionExpiryNanos;
    private long requestLatencyMs;
    private ReceiverOptions<Integer, String> receiverOptions;
    private ConsumerRebalanceListener rebalanceCallback;
    private boolean assignmentPending = false;
    private boolean closed;

    public MockConsumer(MockCluster cluster, boolean autoHeartbeat) {
        executor = Executors.newSingleThreadScheduledExecutor();
        consumerLock = new ReentrantLock();
        assignment = new HashSet<>();
        subscription = new HashSet<>();
        paused = new HashSet<>();
        offsets = new HashMap<>();
        pollExceptions = new ConcurrentLinkedQueue<>();
        commitExceptions = new ConcurrentLinkedQueue<>();
        this.cluster = cluster;
        this.autoHeartbeat = autoHeartbeat;
        this.requestLatencyMs = 10;
    }

    public void configure(ReceiverOptions<Integer, String> receiverOptions) {
        this.receiverOptions = receiverOptions;
        if (!autoHeartbeat)
            sessionTimeout = Duration.ofMillis(ConsumerFactory.INSTANCE.getLongOption(receiverOptions, ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 2000L));
        else
            sessionTimeout = null;
    }

    public boolean isClosed() {
        return closed;
    }

    public void addPollException(KafkaException exception, int count) {
        for (int i = 0; i < count; i++)
            pollExceptions.add(exception);
    }

    public void addCommitException(KafkaException exception, int count) {
        for (int i = 0; i < count; i++)
            commitExceptions.add(exception);
    }

    @Override
    public Set<TopicPartition> assignment() {
        return assignment;
    }

    @Override
    public Set<String> subscription() {
        return subscription;
    }

    @Override
    public void subscribe(Collection<String> topics) {
        subscribe(topics, null);
    }

    @Override
    public void subscribe(Collection<String> topics, ConsumerRebalanceListener callback) {
        acquire();
        try {
            this.rebalanceCallback = callback;
            doUnAssign();
            subscription.clear();
            subscription.addAll(topics);
            for (String topic : topics) {
                List<PartitionInfo> partitions = cluster.cluster().availablePartitionsForTopic(topic);
                if (partitions != null) {
                    for (PartitionInfo p : partitions) {
                        assignment.add(new TopicPartition(p.topic(), p.partition()));
                    }
                }
            }
            if (sessionTimeout != null)
                sessionExpiryNanos = System.nanoTime() + sessionTimeout.toNanos();
            assignmentPending = true;
        } finally {
            release();
        }
    }

    @Override
    public void assign(Collection<TopicPartition> partitions) {
        acquire();
        try {
            rebalanceCallback = null;
            subscription.clear();
            doUnAssign();
            assignment.addAll(partitions);
            assignmentPending = true;
        } finally {
            release();
        }
    }

    @Override
    public void subscribe(Pattern pattern, ConsumerRebalanceListener callback) {
        acquire();
        try {
            Set<String> topics = new HashSet<>();
            Set<String> allTopics = cluster.cluster().topics();
            for (String topic : allTopics) {
                if (pattern.matcher(topic).matches())
                    topics.add(topic);
            }
            subscribe(topics, callback);
        } finally {
            release();
        }
    }

    @Override
    public void unsubscribe() {
        acquire();
        try {
            doUnAssign();
            subscription.clear();
        } finally {
            release();
        }
    }

    @Override
    public ConsumerRecords<Integer, String> poll(long timeout) {
        acquire();
        try {
            Map<TopicPartition, List<ConsumerRecord<Integer, String>>> records = new HashMap<>();
            if (assignmentPending) {
                doAssign();
                assignmentPending = false;
                return new ConsumerRecords<Integer, String>(records);
            } else if (sessionTimeout != null && sessionExpiryNanos > 0 && System.nanoTime() > sessionExpiryNanos) {
                doUnAssign();
            }
            try {
                Thread.sleep(requestLatencyMs);
            } catch (InterruptedException e) {
                throw new KafkaException(e);
            }
            KafkaException exception;
            if ((exception = pollExceptions.poll()) != null)
                throw exception;
            int count = 0;
            for (TopicPartition partition : assignment) {
                records.put(partition, new ArrayList<>());
                long offset = offsets.get(partition);
                List<Message> log = cluster.log(partition);
                if (log.size() > offset) {
                    Message message = log.get((int) offset);
                    ConsumerRecord<Integer, String> record = new ConsumerRecord<Integer, String>(partition.topic(), partition.partition(), offset,
                            message.timestamp(), TimestampType.CREATE_TIME,
                            0, 4, message.value().length(), message.key(), message.value());
                    records.get(partition).add(record);
                    offsets.put(partition, offset + 1);
                    if (count++ == MAX_POLL_RECORDS)
                        break;
                }
            }
            if (sessionTimeout != null)
                sessionExpiryNanos = System.nanoTime() + sessionTimeout.toNanos();
            ConsumerRecords<Integer, String> consumerRecords = new ConsumerRecords<>(records);
            return consumerRecords;
        } finally {
            release();
        }
    }

    @Override
    public void commitSync() {
        throw new UnsupportedOperationException("commitSync");
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        throw new UnsupportedOperationException("commitSync");
    }

    @Override
    public void commitAsync() {
        commitAsync(null);
    }

    @Override
    public void commitAsync(OffsetCommitCallback callback) {
        acquire();
        try {
            Map<TopicPartition, OffsetAndMetadata> commitOffsets = new HashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet())
                commitOffsets.put(entry.getKey(), new OffsetAndMetadata(entry.getValue()));
            commitAsync(commitOffsets, callback);
        } finally {
            release();
        }
    }

    @Override
    public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
        acquire();
        try {
            executor.schedule(() -> {
                    try {
                        KafkaException exception;
                        if ((exception = commitExceptions.poll()) != null)
                            throw exception;
                        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
                            cluster.commitOffset(receiverOptions.groupId(), entry.getKey(), entry.getValue().offset());
                        }
                        callback.onComplete(offsets, null);
                    } catch (Exception e) {
                        callback.onComplete(offsets, e);
                    }
                }, 10, TimeUnit.MILLISECONDS);
        } finally {
            release();
        }
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        acquire();
        try {
            if (offset < 0 || offset > cluster.log(partition).size())
                throw new InvalidOffsetException(partition + "@" + offset);
            offsets.put(partition, offset);
        } finally {
            release();
        }
    }

    @Override
    public void seekToBeginning(Collection<TopicPartition> partitions) {
        acquire();
        try {
            for (TopicPartition partition : partitions)
                offsets.put(partition, 0L);
        } finally {
            release();
        }
    }

    @Override
    public void seekToEnd(Collection<TopicPartition> partitions) {
        acquire();
        try {
            for (TopicPartition partition : partitions)
                offsets.put(partition, (long) cluster.log(partition).size());
        } finally {
            release();
        }
    }

    @Override
    public long position(TopicPartition partition) {
        acquire();
        try {
            return offsets.get(partition);
        } finally {
            release();
        }
    }

    @Override
    public OffsetAndMetadata committed(TopicPartition partition) {
        acquire();
        try {
            Long offset = cluster.committedOffset(receiverOptions.groupId(), partition);
            return offset == null ? null : new OffsetAndMetadata(offset);
        } finally {
            release();
        }
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return null;
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        acquire();
        try {
            return cluster.cluster().partitionsForTopic(topic);
        } finally {
            release();
        }
    }

    @Override
    public Map<String, List<PartitionInfo>> listTopics() {
        acquire();
        try {
            Map<String, List<PartitionInfo>> topicInfo = new HashMap<>();
            Set<String> topics = cluster.cluster().topics();
            for (String topic : topics)
                topicInfo.put(topic, cluster.cluster().partitionsForTopic(topic));
            return topicInfo;
        } finally {
            release();
        }
    }

    @Override
    public Set<TopicPartition> paused() {
        return paused;
    }

    @Override
    public void pause(Collection<TopicPartition> partitions) {
        acquire();
        try {
            paused.addAll(partitions);
        } finally {
            release();
        }
    }

    @Override
    public void resume(Collection<TopicPartition> partitions) {
        acquire();
        try {
            paused.removeAll(partitions);
        } finally {
            release();
        }
    }

    @Override
    public void close() {
        acquire();
        try {
            executor.shutdown();
            executor.awaitTermination(receiverOptions.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            closed = true;
        } catch (InterruptedException e) {
            // ignore
        } finally {
            release();
        }
    }

    @Override
    public void wakeup() {
    }

    private void doAssign() {
        if (assignment.size() > 0 && rebalanceCallback != null)
            rebalanceCallback.onPartitionsAssigned(assignment);
        for (TopicPartition partition : assignment) {
            Long offset = cluster.committedOffset(receiverOptions.groupId(), partition);
            if (offset == null) {
                String reset = (String) receiverOptions.consumerProperties().getOrDefault(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
                switch (reset) {
                    case "earliest":
                        offset = 0L;
                        break;
                    case "latest":
                        offset = (long) cluster.log(partition).size();
                        break;
                    default:
                        throw new KafkaException("Offset not available");
                }
            }
            offsets.putIfAbsent(partition, offset);
        }
    }

    private void doUnAssign() {
        if (assignment.size() > 0 && rebalanceCallback != null)
            rebalanceCallback.onPartitionsRevoked(assignment);
        assignment.clear();
        paused.clear();
        offsets.clear();
    }

    private void acquire() {
        if (!consumerLock.tryLock())
            throw new ConcurrentModificationException("Consumer is not thread-safe");
    }

    private void release() {
        consumerLock.unlock();
    }

    public static class Pool extends ConsumerFactory {
        private final List<MockConsumer> freeConsumers = new ArrayList<>();
        private final List<MockConsumer> consumersInUse = new ArrayList<>();
        public Pool(List<MockConsumer> freeConsumers) {
            this.freeConsumers.addAll(freeConsumers);
        }
        @SuppressWarnings("unchecked")
        public <K, V> Consumer<K, V> createConsumer(ReceiverOptions<K, V> receiverOptions) {
            MockConsumer consumer = freeConsumers.remove(0);
            consumer.configure((ReceiverOptions<Integer, String>) receiverOptions);
            consumersInUse.add(consumer);
            return (Consumer<K, V>) consumer;
        }
        public List<MockConsumer> consumersInUse() {
            return consumersInUse;
        }
        public void addConsumer(MockConsumer consumer) {
            freeConsumers.add(consumer);
        }
    }
}
