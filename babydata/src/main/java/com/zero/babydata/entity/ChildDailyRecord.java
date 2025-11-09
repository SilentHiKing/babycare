package com.zero.babydata.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "ChildDailyRecord",
        foreignKeys = @ForeignKey(entity = BabyInfo.class,
                parentColumns = "babyId",
                childColumns = "babyId",
                onDelete = ForeignKey.CASCADE), indices = {@Index(value = "babyId")})
public class ChildDailyRecord {

    @PrimaryKey(autoGenerate = true)
    public int recordId;  // 记录唯一标识符

    public int babyId;  // 外键，关联到婴儿信息表

    public Long recordDate;  // 记录日期，例如 2025-10-15

    public float weight;  // 体重（单位：kg）

    public float height;// 身高（单位：cm）


    public float headCircumference; // 头围 cm

    public String pic;  // 可空的字符串字段，保存图片的URL或路径

    public String extra;  // 可空的字符串字段，用于存储额外信息

}
