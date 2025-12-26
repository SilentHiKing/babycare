package com.zero.babydata.entity

/**
 * 辅食类型
 * 
 * 当 FeedingRecord.feedingType = FeedingType.SOLID_FOOD 时，
 * 使用此类型细分辅食种类
 */
object SolidFoodType {
    
    // ==================== 1x: 主食类 ====================
    const val CATEGORY_STAPLE = 10
    
    /** 米糊/米粉 */
    const val STAPLE_RICE_CEREAL = 11
    /** 面条 */
    const val STAPLE_NOODLE = 12
    /** 粥 */
    const val STAPLE_PORRIDGE = 13
    /** 馒头/面包 */
    const val STAPLE_BREAD = 14
    /** 米饭 */
    const val STAPLE_RICE = 15
    
    // ==================== 2x: 蔬菜类 ====================
    const val CATEGORY_VEGETABLE = 20
    
    /** 蔬菜泥 */
    const val VEGETABLE_PUREE = 21
    /** 蔬菜块/条 */
    const val VEGETABLE_PIECES = 22
    
    // ==================== 3x: 水果类 ====================
    const val CATEGORY_FRUIT = 30
    
    /** 水果泥 */
    const val FRUIT_PUREE = 31
    /** 水果块 */
    const val FRUIT_PIECES = 32
    
    // ==================== 4x: 蛋白质类 ====================
    const val CATEGORY_PROTEIN = 40
    
    /** 蛋黄 */
    const val PROTEIN_EGG_YOLK = 41
    /** 全蛋 */
    const val PROTEIN_WHOLE_EGG = 42
    /** 肉泥 */
    const val PROTEIN_MEAT_PUREE = 43
    /** 鱼泥 */
    const val PROTEIN_FISH_PUREE = 44
    /** 虾泥 */
    const val PROTEIN_SHRIMP_PUREE = 45
    /** 豆腐 */
    const val PROTEIN_TOFU = 46
    
    // ==================== 5x: 奶制品类 ====================
    const val CATEGORY_DAIRY = 50
    
    /** 酸奶 */
    const val DAIRY_YOGURT = 51
    /** 奶酪 */
    const val DAIRY_CHEESE = 52
    
    // ==================== 6x: 饮品类 ====================
    const val CATEGORY_DRINK = 60
    
    /** 水 */
    const val DRINK_WATER = 61
    /** 果汁 */
    const val DRINK_JUICE = 62
    /** 汤 */
    const val DRINK_SOUP = 63
    
    // ==================== 7x: 营养补充类 ====================
    const val CATEGORY_SUPPLEMENT = 70
    
    /** 维生素D */
    const val SUPPLEMENT_VITAMIN_D = 71
    /** 铁剂 */
    const val SUPPLEMENT_IRON = 72
    /** 钙 */
    const val SUPPLEMENT_CALCIUM = 73
    /** DHA */
    const val SUPPLEMENT_DHA = 74
    /** 益生菌 */
    const val SUPPLEMENT_PROBIOTIC = 75
    /** 其他维生素/营养剂 */
    const val SUPPLEMENT_OTHER = 79
    
    // ==================== 8x: 零食类 ====================
    const val CATEGORY_SNACK = 80
    
    /** 溶豆 */
    const val SNACK_PUFF = 81
    /** 磨牙棒/饼干 */
    const val SNACK_TEETHING_BISCUIT = 82
    /** 米饼 */
    const val SNACK_RICE_CRACKER = 83
    
    // ==================== 9x: 其他 ====================
    const val CATEGORY_OTHER = 90
    
    /** 自定义/其他 */
    const val OTHER = 99
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取大类
     */
    fun getCategory(type: Int): Int = (type / 10) * 10
    
    /**
     * 判断是否需要记录具体食物名称
     * 如蔬菜泥需要记录是什么蔬菜
     */
    fun requiresFoodName(type: Int): Boolean = when (type) {
        VEGETABLE_PUREE, VEGETABLE_PIECES,
        FRUIT_PUREE, FRUIT_PIECES,
        PROTEIN_MEAT_PUREE, PROTEIN_FISH_PUREE,
        DRINK_JUICE, DRINK_SOUP,
        SUPPLEMENT_OTHER, OTHER -> true
        else -> false
    }
    
    /**
     * 获取默认单位
     */
    fun getDefaultUnit(type: Int): String = when (getCategory(type)) {
        CATEGORY_DRINK -> "ml"
        CATEGORY_SUPPLEMENT -> "滴"
        else -> "g"
    }
    
    /**
     * 是否首次添加时需要关注过敏
     */
    fun isAllergenRisk(type: Int): Boolean = when (type) {
        PROTEIN_EGG_YOLK, PROTEIN_WHOLE_EGG,
        PROTEIN_FISH_PUREE, PROTEIN_SHRIMP_PUREE,
        DAIRY_YOGURT, DAIRY_CHEESE,
        STAPLE_BREAD -> true
        else -> false
    }
    
    /**
     * 获取分类下的子类型列表
     * @return List of Pair(type, stringResId)
     */
    fun getSubtypes(category: Int): List<Pair<Int, Int>> = when (category) {
        CATEGORY_STAPLE -> listOf(
            STAPLE_RICE_CEREAL to com.zero.common.R.string.solid_rice_cereal,
            STAPLE_NOODLE to com.zero.common.R.string.solid_noodle,
            STAPLE_PORRIDGE to com.zero.common.R.string.solid_porridge,
            STAPLE_BREAD to com.zero.common.R.string.solid_bread,
            STAPLE_RICE to com.zero.common.R.string.solid_rice
        )
        CATEGORY_VEGETABLE -> listOf(
            VEGETABLE_PUREE to com.zero.common.R.string.solid_vegetable_puree,
            VEGETABLE_PIECES to com.zero.common.R.string.solid_vegetable_pieces
        )
        CATEGORY_FRUIT -> listOf(
            FRUIT_PUREE to com.zero.common.R.string.solid_fruit_puree,
            FRUIT_PIECES to com.zero.common.R.string.solid_fruit_pieces
        )
        CATEGORY_PROTEIN -> listOf(
            PROTEIN_EGG_YOLK to com.zero.common.R.string.solid_egg_yolk,
            PROTEIN_WHOLE_EGG to com.zero.common.R.string.solid_whole_egg,
            PROTEIN_MEAT_PUREE to com.zero.common.R.string.solid_meat_puree,
            PROTEIN_FISH_PUREE to com.zero.common.R.string.solid_fish_puree,
            PROTEIN_SHRIMP_PUREE to com.zero.common.R.string.solid_shrimp_puree,
            PROTEIN_TOFU to com.zero.common.R.string.solid_tofu
        )
        CATEGORY_DAIRY -> listOf(
            DAIRY_YOGURT to com.zero.common.R.string.solid_yogurt,
            DAIRY_CHEESE to com.zero.common.R.string.solid_cheese
        )
        CATEGORY_DRINK -> listOf(
            DRINK_WATER to com.zero.common.R.string.solid_water,
            DRINK_JUICE to com.zero.common.R.string.solid_juice,
            DRINK_SOUP to com.zero.common.R.string.solid_soup
        )
        CATEGORY_SUPPLEMENT -> listOf(
            SUPPLEMENT_VITAMIN_D to com.zero.common.R.string.solid_vitamin_d,
            SUPPLEMENT_IRON to com.zero.common.R.string.solid_iron,
            SUPPLEMENT_CALCIUM to com.zero.common.R.string.solid_calcium,
            SUPPLEMENT_DHA to com.zero.common.R.string.solid_dha,
            SUPPLEMENT_PROBIOTIC to com.zero.common.R.string.solid_probiotic,
            SUPPLEMENT_OTHER to com.zero.common.R.string.solid_supplement_other
        )
        CATEGORY_SNACK -> listOf(
            SNACK_PUFF to com.zero.common.R.string.solid_puff,
            SNACK_TEETHING_BISCUIT to com.zero.common.R.string.solid_teething_biscuit,
            SNACK_RICE_CRACKER to com.zero.common.R.string.solid_rice_cracker
        )
        else -> emptyList()
    }
}

