package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.BlockType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "story_block")
public class StoryBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "block_type")
    @Enumerated(EnumType.STRING)
    private BlockType blockType;

    @Column(name = "ref_type")
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "sort_time")
    private Date sortTime;

    @Column(name = "sort_index")
    private Integer sortIndex;

    @Column(name = "title")
    private String title;

    @Column(name = "text_content")
    private String textContent;

    @Column(name = "cover_object_key")
    private String coverObjectKey;

    @Column(name = "is_hidden")
    private Boolean isHidden;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;

}
