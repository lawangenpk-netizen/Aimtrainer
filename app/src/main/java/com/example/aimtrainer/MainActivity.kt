package com.example.aimtrainer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val table = TableView(this)

        val bounces = Button(this)
        fun label() { bounces.text = "Bounces: ${table.maxBounces}" }
        label()
        bounces.setOnClickListener {
            table.maxBounces = (table.maxBounces + 1) % 4
            label()
        }

        val reset = Button(this).apply {
            text = "Reset"
            setOnClickListener { table.reset() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bounces)
            addView(reset)
        }

        val root = FrameLayout(this)
        root.addView(table, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        setContentView(root)
    }
}
