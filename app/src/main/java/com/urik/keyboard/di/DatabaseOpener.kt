package com.urik.keyboard.di

import android.content.Context
import com.urik.keyboard.data.database.KeyboardDatabase

interface DatabaseOpener {
    fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase
    fun reset()
}
