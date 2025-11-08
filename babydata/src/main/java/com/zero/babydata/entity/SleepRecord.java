package com.zero.babydata.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "Sleep_Records",
        foreignKeys = @ForeignKey(entity = BabyInfo.class,
                parentColumns = "babyId",
                childColumns = "babyId",
                onDelete = ForeignKey.CASCADE), indices = {@Index(value = "babyId")})
public class SleepRecord {
    @PrimaryKey(autoGenerate = true)
    public int sleepId;
    public int babyId;
    public Long sleepStart;
    public Long sleepEnd;
    public int sleepDuration;
    public String note;
    public Long createdAt;
}
