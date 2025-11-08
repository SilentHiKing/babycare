package com.zero.babydata.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.text.SimpleDateFormat;
import java.util.Date;

@Entity(tableName = "Baby_Info")
public class BabyInfo {
    @PrimaryKey(autoGenerate = true)
    public int babyId;

    public String name;
    public String gender;
    public Long birthDate;
    public float birthWeight;
    public float birthHeight;
    public String bloodType;  // 新增字段：血型
    public String extra;  // 新增字段：血型


    public String generateCurrentData(){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        String currentDateAndTime = sdf.format(new Date());
        return currentDateAndTime;
    }
}
