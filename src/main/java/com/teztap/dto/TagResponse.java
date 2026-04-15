package com.teztap.dto;

import lombok.Data;

import java.util.List;

@Data
public class TagResponse {
    private Long productId;
    private List<String> tags;
}
