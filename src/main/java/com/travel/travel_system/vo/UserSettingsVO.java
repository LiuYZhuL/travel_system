package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private PrivacyModeVO defaultPrivacyMode;
    private Boolean canDeleteAllData;
    private Boolean canLogout;
}