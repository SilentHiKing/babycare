package com.zero.babydata.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "Event_Records",
        foreignKeys = @ForeignKey(entity = BabyInfo.class,
                parentColumns = "babyId",
                childColumns = "babyId",
                onDelete = ForeignKey.CASCADE), indices = {@Index(value = "babyId")}
)

public class EventRecord {
    @PrimaryKey(autoGenerate = true)
    public int eventId;
    public int type;
    public int babyId;
    public Long time;
    public String note;
    public Long createdAt;
}
