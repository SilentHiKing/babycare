package com.zero.babydata.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "Feeding_Records",
        foreignKeys = @ForeignKey(entity = BabyInfo.class,
                parentColumns = "babyId",
                childColumns = "babyId",
                onDelete = ForeignKey.CASCADE), indices = {@Index(value = "babyId")}
)

public class FeedingRecord {
    @PrimaryKey(autoGenerate = true)
    public int feedingId;

    public int babyId;
    public Long feedingStart;
    public Long feedingEnd;
    public int feedingDuration;
    public String note;
    public Long createdAt;
}
