package com.facebook.presto.serde;

import com.facebook.presto.TupleInfo;
import com.facebook.presto.nblock.Block;
import com.facebook.presto.nblock.Blocks;
import com.facebook.presto.slice.SliceInput;
import com.facebook.presto.slice.SliceOutput;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.InputSupplier;

import java.io.IOException;
import java.util.Iterator;

import static com.google.common.base.Preconditions.checkNotNull;

public class BlocksSerde
{
    public BlocksWriter createBlocksWriter(final SliceOutput sliceOutput)
    {
        checkNotNull(sliceOutput, "sliceOutput is null");
        return new BlocksWriter() {
            private BlocksWriter blocksWriter;

            @Override
            public BlocksWriter append(Block block)
            {
                Preconditions.checkNotNull(block, "block is null");

                if (blocksWriter == null) {
                    BlockSerde blockSerde = BlockSerdes.getSerdeForBlock(block);
                    blocksWriter = blockSerde.createBlockWriter(sliceOutput);

                    BlockSerdeSerde.writeBlockSerde(sliceOutput, blockSerde);
                    TupleInfoSerde.writeTupleInfo(block.getTupleInfo(), sliceOutput);
                }

                blocksWriter.append(block);
                return this;
            }

            @Override
            public void finish()
            {
                blocksWriter.finish();
            }
        };
    }

    public void writeBlocks(SliceOutput sliceOutput, Block... blocks) {
        writeBlocks(sliceOutput, ImmutableList.copyOf(blocks).iterator());
    }

    public void writeBlocks(SliceOutput sliceOutput, Iterable<? extends Block> blocks) {
        writeBlocks(sliceOutput, blocks.iterator());
    }

    public void writeBlocks(SliceOutput sliceOutput, Iterator<? extends Block> blocks) {
        BlocksWriter blocksWriter = createBlocksWriter(sliceOutput);
        while (blocks.hasNext()) {
            blocksWriter.append(blocks.next());
        }
        blocksWriter.finish();
    }

    public Blocks readBlocks(InputSupplier<SliceInput> sliceInputSupplier)
    {
        return readBlocks(sliceInputSupplier, 0);
    }

    public Blocks readBlocks(final InputSupplier<SliceInput> sliceInputSupplier, final long startPosition)
    {
        Preconditions.checkNotNull(sliceInputSupplier, "sliceInputSupplier is null");
        Preconditions.checkArgument(startPosition >= 0, "startPosition is negative");

        return new Blocks()
        {
            @Override
            public Iterator<Block> iterator()
            {
                try {
                    return readBlocks(sliceInputSupplier.getInput(), startPosition);
                }
                catch (IOException e) {
                    throw Throwables.propagate(e);
                }
            }
        };
    }

    public Iterator<Block> readBlocks(SliceInput input, long startPosition)
    {
        return new BlocksReader(input, startPosition);
    }

    private static class BlocksReader
            extends AbstractIterator<Block>
    {
        private final SliceInput sliceInput;
        private final BlockSerde blockSerde;
        private final TupleInfo tupleInfo;
        private long positionOffset;

        private BlocksReader(SliceInput sliceInput, long startPosition)
        {
            this.positionOffset = startPosition;
            this.sliceInput = sliceInput;

            blockSerde = BlockSerdeSerde.readBlockSerde(sliceInput);
            tupleInfo = TupleInfoSerde.readTupleInfo(sliceInput);
        }

        protected Block computeNext()
        {
            if (!sliceInput.isReadable()) {
                return endOfData();
            }

            Block block = blockSerde.readBlock(sliceInput, tupleInfo, positionOffset);
            positionOffset += block.getCount();
            return block;
        }
    }
}
