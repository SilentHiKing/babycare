package com.zero.babydata.entity;

public enum FeedingType {

    /**
     * 母乳
     */
    BREAST(0),

    /**
     * 奶粉
     */
    FORMULA(1),

    /**
     * 辅食（米糊、蔬菜泥、水果泥等）
     */
    SOLID_FOOD(2),

    /**
     * 母乳 + 奶粉 混合喂养
     */
    MIXED(3),

    /**
     * 其他（手动输入）
     */
    OTHER(4);

    private final int type; // 原始整数值

    FeedingType(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }

    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        switch (this) {
            case BREAST:
                return "母乳喂养";
            case FORMULA:
                return "奶粉喂养";
            case MIXED:
                return "混合喂养";
            case SOLID_FOOD:
                return "辅食";
            case OTHER:
            default:
                return "其他";
        }
    }


    /**
     * 根据原始值获取枚举（数据库/网络反序列化）
     */
    public static FeedingType fromType(int type) {
        for (FeedingType t : values()) {
            if (t.getType() == type) {
                return t;
            }
        }
        return OTHER; // 默认返回 OTHER，防止 null
    }
}
