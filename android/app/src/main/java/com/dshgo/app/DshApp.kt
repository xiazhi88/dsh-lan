package com.dshgo.app

import android.app.Application

/**
 * 装上全局崩溃钩子。
 *
 * 只做一件事：记录。记录完仍然交还给系统默认处理器，**不吞异常**——
 * 吞掉会让 app 卡在半死状态，比直接闪退更难排查。
 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record(applicationContext, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
