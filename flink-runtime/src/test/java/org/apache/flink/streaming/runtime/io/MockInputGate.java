/*
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

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.IndexedInputGate;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Mock {@link InputGate}. */
public class MockInputGate extends IndexedInputGate {

    private final int numberOfChannels;

    private final Queue<BufferOrEvent> bufferOrEvents;

    private final boolean[] closed;

    private final boolean finishAfterLastBuffer;

    private ArrayList<Integer> lastUnblockedChannels = new ArrayList<>();

    private Set<Integer> blockedChannels = new HashSet<>();

    public MockInputGate(int numberOfChannels, List<BufferOrEvent> bufferOrEvents) {
        this(numberOfChannels, bufferOrEvents, true);
    }

    public MockInputGate(
            int numberOfChannels,
            List<BufferOrEvent> bufferOrEvents,
            boolean finishAfterLastBuffer) {
        this.numberOfChannels = numberOfChannels;
        this.bufferOrEvents = new ArrayDeque<BufferOrEvent>(bufferOrEvents);
        this.closed = new boolean[numberOfChannels];
        this.finishAfterLastBuffer = finishAfterLastBuffer;

        availabilityHelper.resetAvailable();
    }

    @Override
    public void setup() {}

    @Override
    public CompletableFuture<Void> getStateConsumedFuture() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void finishReadRecoveredState() {}

    @Override
    public void requestPartitions() {}

    @Override
    public int getNumberOfInputChannels() {
        return numberOfChannels;
    }

    @Override
    public InputChannel getChannel(int channelIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InputChannelInfo> getChannelInfos() {
        return IntStream.range(0, numberOfChannels)
                .mapToObj(channelIndex -> new InputChannelInfo(0, channelIndex))
                .collect(Collectors.toList());
    }

    @Override
    public void checkpointStopped(long cancelledCheckpointId) {
        // ignore, we do not support getChannel method, therefore we can not use the
        // base method
    }

    @Override
    public boolean isFinished() {
        return finishAfterLastBuffer && bufferOrEvents.isEmpty();
    }

    @Override
    public EndOfDataStatus hasReceivedEndOfData() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Optional<BufferOrEvent> getNext() {
        BufferOrEvent next = bufferOrEvents.poll();
        if (!finishAfterLastBuffer && bufferOrEvents.isEmpty()) {
            availabilityHelper.resetUnavailable();
        }
        if (next == null) {
            return Optional.empty();
        }

        int channelIdx = next.getChannelInfo().getInputChannelIdx();
        if (closed[channelIdx]) {
            throw new RuntimeException(
                    "Inconsistent: Channel "
                            + channelIdx
                            + " has data even though it is already closed.");
        }
        if (next.isEvent() && next.getEvent() instanceof EndOfPartitionEvent) {
            closed[channelIdx] = true;
        }
        return Optional.of(next);
    }

    @Override
    public Optional<BufferOrEvent> pollNext() {
        return getNext();
    }

    @Override
    public void sendTaskEvent(TaskEvent event) {}

    @Override
    public void resumeGateConsumption() throws IOException {
        blockedChannels.forEach(
                channelIdx -> resumeConsumption(new InputChannelInfo(getGateIndex(), channelIdx)));
    }

    @Override
    public void resumeConsumption(InputChannelInfo channelInfo) {
        lastUnblockedChannels.add(channelInfo.getInputChannelIdx());
        blockedChannels.remove(channelInfo.getInputChannelIdx());
    }

    @Override
    public void blockConsumption(InputChannelInfo channelInfo) {
        super.blockConsumption(channelInfo);
        if (!blockedChannels.add(channelInfo.getInputChannelIdx())) {
            throw new IllegalArgumentException("Blocking the same channel multiple times");
        }
    }

    @Override
    public ResultPartitionType getConsumedPartitionType() {
        return ResultPartitionType.PIPELINED;
    }

    @Override
    public void triggerDebloating() {}

    public Set<Integer> getBlockedChannels() {
        return blockedChannels;
    }

    @Override
    public void acknowledgeAllRecordsProcessed(InputChannelInfo channelInfo) throws IOException {
        throw new UnsupportedEncodingException();
    }

    @Override
    public void close() {}

    @Override
    public int getGateIndex() {
        return 0;
    }

    @Override
    public List<InputChannelInfo> getUnfinishedChannels() {
        return Collections.emptyList();
    }
}
