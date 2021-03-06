package io.mycat.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@AllArgsConstructor
@Data
@Builder
@EqualsAndHashCode
public class ShardingTableConfig {
    ShardingBackEndTableInfoConfig dataNode;
    ShardingFuntion function;
    String createTableSQL;

    public ShardingTableConfig() {
    }
}