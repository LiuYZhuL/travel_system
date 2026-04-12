package com.travel.travel_system.vo;

import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String url;
    private String fileName;
    private Long fileSize;
}
