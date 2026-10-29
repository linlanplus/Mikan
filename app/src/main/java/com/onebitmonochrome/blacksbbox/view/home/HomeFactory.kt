package com.onebitmonochrome.blacksbbox.view.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.onebitmonochrome.blacksbbox.data.AppsRepository

@Suppress("UNCHECKED_CAST")
class HomeFactory(private val appsRepository: AppsRepository) : ViewModelProvider.NewInstanceFactory() {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(appsRepository) as T
    }
}
