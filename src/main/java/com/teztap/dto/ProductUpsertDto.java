package com.teztap.dto;

import com.teztap.model.Category;
import com.teztap.model.Market;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ProductUpsertDto {
    private final String     name;
    private final String     link;
    private final BigDecimal originalPrice;
    private final BigDecimal discountPrice;   // null if not discounted
    private final BigDecimal discountPercentage;     // null if not discounted
    private final String     imageUrl;
    private final Category category;
    private final Market market;
}
