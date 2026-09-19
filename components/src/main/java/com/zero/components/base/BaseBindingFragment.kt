package com.zero.components.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.zero.components.viewbinding.FragmentBinding
import com.zero.components.viewbinding.FragmentBindingDelegate



abstract class BaseBindingFragment<VB : ViewBinding> : Fragment(),
  FragmentBinding<VB> by FragmentBindingDelegate() {

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
    createViewWithBinding(inflater, container)

  override fun onDestroyView() {
    // 子类先释放 View 监听再调用 super；同步清理防止同一帧重建复用旧绑定或被延迟任务清空。
    super.onDestroyView()
    clearBinding()
  }
}
