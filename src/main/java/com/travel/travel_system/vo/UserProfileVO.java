package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String nickname;
    private String avatarUrl;
    private PrivacyModeVO defaultPrivacyMode;
    private String createdAt;
}