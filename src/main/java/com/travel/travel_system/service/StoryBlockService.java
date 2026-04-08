package com.travel.travel_system.service;

import com.travel.travel_system.model.StoryBlock;
import com.travel.travel_system.model.enums.BlockType;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface StoryBlockService {

    StoryBlock createStoryBlock(StoryBlock storyBlock);

    Optional<StoryBlock> getStoryBlock(Long blockId);

    List<StoryBlock> getStoryBlocksByTrip(Long tripId);

    List<StoryBlock> getStoryBlocksByTripAndType(Long tripId, BlockType blockType);

    List<StoryBlock> getVisibleStoryBlocks(Long tripId);

    List<StoryBlock> getHiddenStoryBlocks(Long tripId);

    StoryBlock updateStoryBlock(Long blockId, String title, String textContent, Boolean isHidden);

    StoryBlock updateSortOrder(Long blockId, Date sortTime, Integer sortIndex);

    void deleteStoryBlock(Long blockId);

    void deleteStoryBlocksByTrip(Long tripId);

    long countByTrip(Long tripId);

    Optional<StoryBlock> getFirstBlock(Long tripId);

    Optional<StoryBlock> getLastBlock(Long tripId);
}
