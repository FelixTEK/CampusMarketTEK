package com.example.campusmarkettek

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** จุดเริ่มต้นของ Hilt — ทุก dependency ที่ inject ได้มีอายุผูกกับ Application นี้ */
@HiltAndroidApp
class CampusMarketApp : Application()
