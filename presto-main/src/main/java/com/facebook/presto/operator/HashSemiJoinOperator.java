/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.block.uncompressed.UncompressedBlock;
import com.facebook.presto.operator.SetBuilderOperator.SetSupplier;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.slice.Slices;

import java.util.List;

import static com.facebook.presto.util.MoreFutures.tryGetUnchecked;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class HashSemiJoinOperator
        implements Operator
{
    private final OperatorContext operatorContext;

    public static class HashSemiJoinOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final SetSupplier setSupplier;
        private final List<TupleInfo> probeTupleInfos;
        private final int probeJoinChannel;
        private final List<TupleInfo> tupleInfos;
        private boolean closed;

        public HashSemiJoinOperatorFactory(int operatorId, SetSupplier setSupplier, List<TupleInfo> probeTupleInfos, int probeJoinChannel)
        {
            this.operatorId = operatorId;
            this.setSupplier = setSupplier;
            this.probeTupleInfos = probeTupleInfos;
            checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");
            this.probeJoinChannel = probeJoinChannel;

            this.tupleInfos = ImmutableList.<TupleInfo>builder()
                    .addAll(probeTupleInfos)
                    .add(TupleInfo.SINGLE_BOOLEAN)
                    .build();
        }

        @Override
        public List<TupleInfo> getTupleInfos()
        {
            return tupleInfos;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, HashBuilderOperator.class.getSimpleName());
            return new HashSemiJoinOperator(operatorContext, setSupplier, probeTupleInfos, probeJoinChannel);
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    private final int probeJoinChannel;
    private final List<TupleInfo> tupleInfos;
    private final ListenableFuture<ChannelSet> channelSetFuture;

    private ChannelSet channelSet;
    private Page outputPage;
    private boolean finishing;

    public HashSemiJoinOperator(OperatorContext operatorContext, SetSupplier channelSetFuture, List<TupleInfo> probeTupleInfos, int probeJoinChannel)
    {
        this.operatorContext = checkNotNull(operatorContext, "operatorContext is null");

        // todo pass in desired projection
        checkNotNull(channelSetFuture, "hashProvider is null");
        checkNotNull(probeTupleInfos, "probeTupleInfos is null");
        checkArgument(probeJoinChannel >= 0, "probeJoinChannel is negative");
        checkArgument(probeTupleInfos.get(probeJoinChannel).getFieldCount() == 1, "Semi join currently only support simple types");

        this.channelSetFuture = channelSetFuture.getChannelSet();
        this.probeJoinChannel = probeJoinChannel;

        this.tupleInfos = ImmutableList.<TupleInfo>builder()
                .addAll(probeTupleInfos)
                .add(TupleInfo.SINGLE_BOOLEAN)
                .build();
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public List<TupleInfo> getTupleInfos()
    {
        return tupleInfos;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        return finishing && outputPage == null;
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        return channelSetFuture;
    }

    @Override
    public boolean needsInput()
    {
        if (finishing || outputPage != null) {
            return false;
        }

        if (channelSet == null) {
            channelSet = tryGetUnchecked(channelSetFuture);
        }
        return channelSet != null;
    }

    @Override
    public void addInput(Page page)
    {
        checkNotNull(page, "page is null");
        checkState(!finishing, "Operator is finishing");
        checkState(channelSet != null, "Set has not been built yet");
        checkState(outputPage == null, "Operator still has pending output");

        // update hashing strategy to use probe block
        UncompressedBlock probeJoinBlock = (UncompressedBlock) page.getBlock(probeJoinChannel);
        channelSet.setLookupSlice(probeJoinBlock.getSlice());

        // create the block builder for the new boolean column
        // we know the exact size required for the block
        int blockSize = page.getPositionCount() * TupleInfo.SINGLE_BOOLEAN.getFixedSize();
        BlockBuilder blockBuilder = new BlockBuilder(TupleInfo.SINGLE_BOOLEAN, blockSize, Slices.allocate(blockSize).getOutput());

        BlockCursor probeJoinCursor = probeJoinBlock.cursor();
        for (int position = 0; position < page.getPositionCount(); position++) {
            checkState(probeJoinCursor.advanceNextPosition());
            if (probeJoinCursor.isNull(0)) {
                blockBuilder.appendNull();
            }
            else {
                boolean contains = channelSet.contains(probeJoinCursor);
                if (!contains && channelSet.containsNull()) {
                    blockBuilder.appendNull();
                }
                else {
                    blockBuilder.append(contains);
                }
            }
        }

        // add the new boolean column to the page
        Block[] sourceBlocks = page.getBlocks();
        Block[] outputBlocks = new Block[sourceBlocks.length + 1]; // +1 for the single boolean output channel

        System.arraycopy(sourceBlocks, 0, outputBlocks, 0, sourceBlocks.length);
        outputBlocks[sourceBlocks.length] = blockBuilder.build();

        outputPage = new Page(outputBlocks);
    }

    @Override
    public Page getOutput()
    {
        Page result = outputPage;
        outputPage = null;
        return result;
    }
}