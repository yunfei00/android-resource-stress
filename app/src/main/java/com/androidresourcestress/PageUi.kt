package com.androidresourcestress

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object PageUi {
    fun content(activity: Activity, title: String): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 24), dp(activity, 16), dp(activity, 24))
            setBackgroundColor(activity.getColor(R.color.background))
        }
        root.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTextColor(activity.getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(activity, 12))
        })
        root.addView(Button(activity).apply {
            text = "BACK"
            setOnClickListener { activity.finish() }
        }, matchWrap())
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(activity.getColor(R.color.background))
            addView(root)
        })
        return root
    }

    fun body(activity: Activity, textValue: String = ""): TextView = TextView(activity).apply {
        text = textValue
        textSize = 14f
        setTextColor(activity.getColor(R.color.text_primary))
        setLineSpacing(0f, 1.18f)
        setPadding(0, dp(activity, 12), 0, dp(activity, 12))
    }

    fun secondary(activity: Activity, textValue: String = ""): TextView = TextView(activity).apply {
        text = textValue
        textSize = 12f
        setTextColor(activity.getColor(R.color.text_secondary))
        setPadding(0, dp(activity, 8), 0, dp(activity, 8))
    }

    fun button(activity: Activity, label: String, onClick: (View) -> Unit): Button =
        Button(activity).apply {
            text = label
            setOnClickListener(onClick)
        }

    fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
