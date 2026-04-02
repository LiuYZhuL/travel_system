package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPermissionStateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean locationAuthorized;
    private Boolean backgroundLocationEnabled;
    private Boolean albumAuthorized;
    private Boolean cameraAuthorized;
    private String lastSyncTime;
    private Boolean collecting;
}