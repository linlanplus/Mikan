package com.onebitmonochrome.blacksbbox.util

import androidx.annotation.StringRes
import com.onebitmonochrome.blacksbbox.app.App


fun getString(@StringRes id:Int,vararg arg:String):String{
    if(arg.isEmpty()){
        return LanguageManager.localized(App.getContext()).getString(id)
    }
    return LanguageManager.localized(App.getContext()).getString(id,*arg)
}

