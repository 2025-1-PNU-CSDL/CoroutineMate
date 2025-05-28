package com.example.coroutinemate

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ------------ DEBUG CODE ------------
        //startActivity(Intent(this, RealtimeCountActivity::class.java))
        //finish()
        // ------------ DEBUG CODE ------------

        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true // 현재 화면
                R.id.nav_workout -> {
                    if (this::class != WorkoutActivity::class) {
                        startActivity(Intent(this, WorkoutActivity::class.java))
                        overridePendingTransition(0, 0)
                        finish()
                    }
                    true
                }
                R.id.nav_records -> {
                    if (this::class != RecordsActivity::class) {
                        startActivity(Intent(this, RecordsActivity::class.java))
                        overridePendingTransition(0, 0)
                        finish()
                    }
                    true
                }
                else -> false
            }
        }
        nav.selectedItemId = R.id.nav_home
    }
}
