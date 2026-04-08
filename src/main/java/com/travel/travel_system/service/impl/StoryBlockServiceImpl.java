package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.StoryBlock;
import com.travel.travel_system.model.enums.BlockType;
import com.travel.travel_system.repository.StoryBlockRepository;
import com.travel.travel_system.service.StoryBlockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class StoryBlockServiceImpl implements StoryBlockService {

    @Autowired
    private StoryBlockRepository storyBlockRepository;

    @Override
    @Transactional
    public StoryBlock createStoryBlock(StoryBlock storyBlock) {
        if (storyBlock.getCreatedAt() == null) {
            storyBlock.setCreatedAt(new Date());
        }
        if (storyBlock.getIsHidden() == null) {
            storyBlock.setIsHidden(false);
        }
        return storyBlockRepository.save(storyBlock);
    }

    @Override
    public Optional<StoryBlock> getStoryBlock(Long blockId) {
        return storyBlockRepository.findById(blockId);
    }

    @Override
    public List<StoryBlock> getStoryBlocksByTrip(Long tripId) {
        return storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId);
    }

    @Override
    public List<StoryBlock> getStoryBlocksByTripAndType(Long tripId, BlockType blockType) {
        return storyBlockRepository.findByTripIdAndBlockTypeOrderBySortTimeAscSortIndexAsc(tripId, blockType.name());
    }

    @Override
    public List<StoryBlock> getVisibleStoryBlocks(Long tripId) {
        return storyBlockRepository.findByTripIdAndIsHiddenFalse(tripId);
    }

    @Override
    public List<StoryBlock> getHiddenStoryBlocks(Long tripId) {
        return storyBlockRepository.findByTripIdAndIsHiddenTrue(tripId);
    }

    @Override
    @Transactional
    public StoryBlock updateStoryBlock(Long blockId, String title, String textContent, Boolean isHidden) {
        StoryBlock block = storyBlockRepository.findById(blockId)
                .orElseThrow(() -> new RuntimeException("故事块不存在，blockId: " + blockId));

        if (title != null) {
            block.setTitle(title);
        }
        if (textContent != null) {
            block.setTextContent(textContent);
        }
        if (isHidden != null) {
            block.setIsHidden(isHidden);
        }
        block.setUpdatedAt(new Date());

        return storyBlockRepository.save(block);
    }

    @Override
    @Transactional
    public StoryBlock updateSortOrder(Long blockId, Date sortTime, Integer sortIndex) {
        StoryBlock block = storyBlockRepository.findById(blockId)
                .orElseThrow(() -> new RuntimeException("故事块不存在，blockId: " + blockId));

        if (sortTime != null) {
            block.setSortTime(sortTime);
        }
        if (sortIndex != null) {
            block.setSortIndex(sortIndex);
        }
        block.setUpdatedAt(new Date());

        return storyBlockRepository.save(block);
    }

    @Override
    @Transactional
    public void deleteStoryBlock(Long blockId) {
        storyBlockRepository.deleteById(blockId);
    }

    @Override
    @Transactional
    public void deleteStoryBlocksByTrip(Long tripId) {
        storyBlockRepository.deleteByTripId(tripId);
    }

    @Override
    public long countByTrip(Long tripId) {
        return storyBlockRepository.countByTripId(tripId);
    }

    @Override
    public Optional<StoryBlock> getFirstBlock(Long tripId) {
        return Optional.ofNullable(storyBlockRepository.findFirstByTripIdOrderBySortTimeAscSortIndexAsc(tripId));
    }

    @Override
    public Optional<StoryBlock> getLastBlock(Long tripId) {
        return Optional.ofNullable(storyBlockRepository.findFirstByTripIdOrderBySortTimeDescSortIndexDesc(tripId));
    }
}
