package com.teztap.dto;

import lombok.Data;

import java.util.List;

@Data
public class SearchResponse {
    private String query;
    private int top_k;
    private List<Match> matches;
}

