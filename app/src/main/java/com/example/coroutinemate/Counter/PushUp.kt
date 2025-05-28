package com.example.coroutinemate.Counter

import android.util.Log
import android.widget.TextView
import com.google.mlkit.vision.pose.Pose
import kotlin.math.atan2
import com.google.mlkit.vision.pose.PoseLandmark as PoseLandmark

// 푸시업 카운터 클래스
class PushUpCounter {
    private var state = "up"
    var count = 0
        private set

    fun process(pose: Pose, countNum: TextView) {
        val shoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val elbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val wrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)

        if (shoulder == null || elbow == null || wrist == null) return

        val angle = calculateAngle(shoulder, elbow, wrist)

        updateState(angle)
        countNum.text = count.toString()
        Log.d("PushUpCounter", "Angle: $angle, State: $state, Count: $count")
    }

    private fun updateState(elbowAngle: Double) {
        val isElbowDown = elbowAngle < 110
        val isElbowUp = elbowAngle > 130

        if (isElbowDown && state == "up") {
            state = "down"
        } else if (isElbowUp && state == "down") {
            state = "up"
            count++
        }
    }

    private fun calculateAngle(p1: PoseLandmark, p2: PoseLandmark, p3: PoseLandmark): Double {
        val angle = Math.toDegrees(
            atan2(p3.position.y - p2.position.y, p3.position.x - p2.position.x).toDouble() -
                    atan2(p1.position.y - p2.position.y, p1.position.x - p2.position.x).toDouble()
        )
        return if (angle < 0) angle + 360 else angle
    }
}