package com.zero.components.base

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import com.zero.components.viewbinding.ActivityBinding
import com.zero.components.viewbinding.ActivityBindingDelegate

abstract class BaseBindingActivity<VB : ViewBinding> : AppCompatActivity(),
    ActivityBinding<VB> by ActivityBindingDelegate() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithBinding()

    }


}