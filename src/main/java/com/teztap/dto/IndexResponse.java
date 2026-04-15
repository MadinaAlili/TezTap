package com.teztap.dto;

import lombok.Data;

import java.util.List;

@Data
public class IndexResponse {

    private int indexed;
    private int skipped;
    private int failed;
    private List<IndexResult> results;
}
