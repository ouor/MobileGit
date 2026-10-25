package com.ouor.mobilegit.ui

import android.content.Context
import androidx.annotation.StringRes

// shortcut: reads strings through the application context so non-@Composable code can use them; a locale change relies on activity recreation.
object Strings { lateinit var context: Context }

fun str(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) Strings.context.getString(id) else Strings.context.getString(id, *args)
