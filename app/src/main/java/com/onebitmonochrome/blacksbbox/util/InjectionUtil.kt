package com.onebitmonochrome.blacksbbox.util

import com.onebitmonochrome.blacksbbox.data.AppsRepository
import com.onebitmonochrome.blacksbbox.data.FakeLocationRepository
import com.onebitmonochrome.blacksbbox.data.GmsRepository

import com.onebitmonochrome.blacksbbox.view.fake.FakeLocationFactory
import com.onebitmonochrome.blacksbbox.view.gms.GmsFactory
import com.onebitmonochrome.blacksbbox.view.home.HomeFactory
import com.onebitmonochrome.blacksbbox.view.list.ListFactory



object InjectionUtil {

    private val appsRepository = AppsRepository()



    private val gmsRepository = GmsRepository()

    private val fakeLocationRepository = FakeLocationRepository()

    fun getHomeFactory(): HomeFactory {
        return HomeFactory(appsRepository)
    }

    fun getListFactory(): ListFactory {
        return ListFactory(appsRepository)
    }


    fun getGmsFactory():GmsFactory{
        return GmsFactory(gmsRepository)
    }

    fun getFakeLocationFactory():FakeLocationFactory{
        return FakeLocationFactory(fakeLocationRepository)
    }
}